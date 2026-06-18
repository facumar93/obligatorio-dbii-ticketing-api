package uy.edu.ucu.ticketing.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Proceso de compra de entradas (camino feliz).
 *
 * El pago se simula como aprobado dentro de la misma transaccion JDBC: una venta
 * PAGA emite sus reservas, entradas y movimientos iniciales de titularidad de
 * forma atomica. El cupo se controla con SELECT ... FOR UPDATE sobre EVENTO_SECTOR
 * para no vender dos veces la ultima entrada ante compras concurrentes.
 */
@RestController
public class VentaController {

    private static final int MAX_ENTRADAS_POR_VENTA = 5;

    private static final List<String> METODOS_PAGO_VALIDOS = List.of(
            "TARJETA_CREDITO",
            "TARJETA_DEBITO",
            "TRANSFERENCIA"
    );

    @PostMapping("/ventas/comprar")
    public ResponseEntity<Map<String, Object>> comprar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) CompraRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return error(HttpStatus.UNAUTHORIZED, e.getCode(), e.getMessage());
        }

        // Normaliza el carrito antes de tocar la base: fusiona lineas repetidas
        // (mismo evento+sector) y valida cantidades y tope de 5 por venta.
        List<LineaNormalizada> lineas;

        try {
            lineas = normalizarCarrito(request);
        } catch (CompraInvalidaException e) {
            return error(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage());
        }

        String metodoPago = (request != null && request.metodoPago != null && !request.metodoPago.isBlank())
                ? request.metodoPago.trim().toUpperCase()
                : "TARJETA_CREDITO";

        if (!METODOS_PAGO_VALIDOS.contains(metodoPago)) {
            return error(HttpStatus.BAD_REQUEST, "METODO_PAGO_INVALIDO",
                    "El metodo de pago debe ser TARJETA_CREDITO, TARJETA_DEBITO o TRANSFERENCIA");
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);

            try {
                Integer idComprador = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

                if (idComprador == null) {
                    connection.rollback();
                    return error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                            "El usuario autenticado no esta registrado en el sistema; debe completar el registro antes de comprar");
                }

                TasaVigente tasa = buscarTasaVigente(connection);

                if (tasa == null) {
                    connection.rollback();
                    return error(HttpStatus.CONFLICT, "TASA_VIGENTE_NO_DISPONIBLE",
                            "No hay una tasa de comision vigente configurada");
                }

                // Bloquea cada EVENTO_SECTOR (en orden determinista) y verifica cupo.
                BigDecimal montoBase = BigDecimal.ZERO;

                for (LineaNormalizada linea : lineas) {
                    EventoSector eventoSector = bloquearEventoSector(connection, linea.idEvento, linea.idSector);

                    if (eventoSector == null) {
                        connection.rollback();
                        return error(HttpStatus.BAD_REQUEST, "EVENTO_SECTOR_INEXISTENTE",
                                "El sector " + linea.idSector + " no esta habilitado para el evento " + linea.idEvento);
                    }

                    int emitidas = contarEntradasVivas(connection, linea.idEvento, linea.idSector);
                    int disponible = eventoSector.capacidadHabilitada - emitidas;

                    if (linea.cantidad > disponible) {
                        connection.rollback();
                        return error(HttpStatus.CONFLICT, "CUPO_INSUFICIENTE",
                                "Cupo insuficiente para el evento " + linea.idEvento + " sector " + linea.idSector
                                        + ": disponibles " + Math.max(disponible, 0) + ", solicitadas " + linea.cantidad);
                    }

                    linea.precioUnitario = eventoSector.precioEntrada;
                    linea.subtotal = eventoSector.precioEntrada.multiply(BigDecimal.valueOf(linea.cantidad));
                    montoBase = montoBase.add(linea.subtotal);
                }

                BigDecimal comision = montoBase
                        .multiply(tasa.porcentaje)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal montoTotal = montoBase.add(comision);

                int idVenta = insertarVenta(connection, idComprador, tasa.idTasaComision, montoBase, montoTotal);

                List<Map<String, Object>> entradasEmitidas = new ArrayList<>();

                for (LineaNormalizada linea : lineas) {
                    int idReserva = insertarReserva(connection, idVenta, linea);

                    for (int i = 0; i < linea.cantidad; i++) {
                        int idEntrada = insertarEntrada(connection, idReserva);
                        insertarMovimientoInicial(connection, idEntrada, idComprador);
                        insertarCredencialQr(connection, idEntrada);

                        Map<String, Object> entrada = new LinkedHashMap<>();
                        entrada.put("idEntrada", idEntrada);
                        entrada.put("idEvento", linea.idEvento);
                        entrada.put("idSector", linea.idSector);
                        entradasEmitidas.add(entrada);
                    }
                }

                insertarIntentoPagoAprobado(connection, idVenta, montoTotal, metodoPago);

                connection.commit();

                Map<String, Object> respuesta = base("OK", "COMPRA_OK");
                respuesta.put("message", "Compra realizada y entradas emitidas correctamente");
                respuesta.put("idVenta", idVenta);
                respuesta.put("idComprador", idComprador);
                respuesta.put("estadoVenta", "PAGA");
                respuesta.put("metodoPago", metodoPago);
                respuesta.put("idTasaComision", tasa.idTasaComision);
                respuesta.put("porcentajeComision", tasa.porcentaje);
                respuesta.put("montoBase", montoBase);
                respuesta.put("comision", comision);
                respuesta.put("montoTotal", montoTotal);
                respuesta.put("cantidadEntradas", entradasEmitidas.size());
                respuesta.put("entradas", entradasEmitidas);

                return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_VENTA",
                        "No se pudo completar la compra en la base de datos");
            }

        } catch (SQLException | IllegalStateException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }

    // --- Normalizacion y validacion del carrito ---

    private static List<LineaNormalizada> normalizarCarrito(CompraRequest request) throws CompraInvalidaException {
        if (request == null || request.lineas == null || request.lineas.isEmpty()) {
            throw new CompraInvalidaException("CARRITO_VACIO", "Debe incluir al menos una linea de compra");
        }

        // Fusiona lineas con el mismo (evento, sector) para evitar violar el
        // UNIQUE(id_venta, id_evento, id_sector) y para sumar bien el tope de 5.
        Map<String, LineaNormalizada> fusionadas = new LinkedHashMap<>();
        int totalEntradas = 0;

        for (LineaCompra linea : request.lineas) {
            if (linea == null
                    || linea.idEvento == null || linea.idEvento <= 0
                    || linea.idSector == null || linea.idSector <= 0) {
                throw new CompraInvalidaException("LINEA_INVALIDA",
                        "Cada linea debe indicar idEvento e idSector validos");
            }

            if (linea.cantidad == null || linea.cantidad < 1) {
                throw new CompraInvalidaException("CANTIDAD_INVALIDA",
                        "La cantidad de cada linea debe ser un entero mayor o igual a 1");
            }

            totalEntradas += linea.cantidad;

            String clave = linea.idEvento + "-" + linea.idSector;
            LineaNormalizada existente = fusionadas.get(clave);

            if (existente == null) {
                fusionadas.put(clave, new LineaNormalizada(linea.idEvento, linea.idSector, linea.cantidad));
            } else {
                existente.cantidad += linea.cantidad;
            }
        }

        if (totalEntradas > MAX_ENTRADAS_POR_VENTA) {
            throw new CompraInvalidaException("LIMITE_ENTRADAS_EXCEDIDO",
                    "Una venta no puede superar las " + MAX_ENTRADAS_POR_VENTA + " entradas (solicitadas " + totalEntradas + ")");
        }

        // Orden determinista de bloqueo para evitar deadlocks entre transacciones.
        List<LineaNormalizada> lineas = new ArrayList<>(fusionadas.values());
        lineas.sort((a, b) -> a.idEvento != b.idEvento
                ? Integer.compare(a.idEvento, b.idEvento)
                : Integer.compare(a.idSector, b.idSector));

        return lineas;
    }

    // --- Acceso a datos ---

    private static Integer buscarIdUsuarioPorFirebaseUid(Connection connection, String firebaseUid) throws SQLException {
        String sql = "SELECT id_usuario FROM USUARIO_GENERAL WHERE firebase_uid = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("id_usuario") : null;
            }
        }
    }

    private static TasaVigente buscarTasaVigente(Connection connection) throws SQLException {
        String sql = """
                SELECT id_tasa_comision, porcentaje
                FROM TASA
                WHERE fecha_hasta IS NULL
                ORDER BY fecha_desde DESC
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            if (resultSet.next()) {
                return new TasaVigente(
                        resultSet.getInt("id_tasa_comision"),
                        resultSet.getBigDecimal("porcentaje")
                );
            }

            return null;
        }
    }

    private static EventoSector bloquearEventoSector(Connection connection, int idEvento, int idSector) throws SQLException {
        String sql = """
                SELECT precio_entrada, capacidad_habilitada
                FROM EVENTO_SECTOR
                WHERE id_evento = ? AND id_sector = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);
            statement.setInt(2, idSector);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new EventoSector(
                            resultSet.getBigDecimal("precio_entrada"),
                            resultSet.getInt("capacidad_habilitada")
                    );
                }

                return null;
            }
        }
    }

    private static int contarEntradasVivas(Connection connection, int idEvento, int idSector) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS emitidas
                FROM ENTRADA e
                JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta
                WHERE r.id_evento = ?
                  AND r.id_sector = ?
                  AND e.estado_entrada IN ('EMITIDA', 'CONSUMIDA')
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);
            statement.setInt(2, idSector);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("emitidas");
            }
        }
    }

    private static int insertarVenta(
            Connection connection,
            int idComprador,
            int idTasaComision,
            BigDecimal montoBase,
            BigDecimal montoTotal) throws SQLException {

        String sql = """
                INSERT INTO VENTA (
                    id_usuario_comprador,
                    id_tasa_comision,
                    estado_venta,
                    monto_base,
                    monto_total
                ) VALUES (?, ?, 'PAGA', ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idComprador);
            statement.setInt(2, idTasaComision);
            statement.setBigDecimal(3, montoBase);
            statement.setBigDecimal(4, montoTotal);
            statement.executeUpdate();

            return obtenerIdGenerado(statement, "id_venta");
        }
    }

    private static int insertarReserva(Connection connection, int idVenta, LineaNormalizada linea) throws SQLException {
        String sql = """
                INSERT INTO RESERVA_POR_VENTA (
                    id_venta,
                    id_evento,
                    id_sector,
                    cantidad,
                    subtotal,
                    estado_reserva
                ) VALUES (?, ?, ?, ?, ?, 'EMITIDA')
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idVenta);
            statement.setInt(2, linea.idEvento);
            statement.setInt(3, linea.idSector);
            statement.setInt(4, linea.cantidad);
            statement.setBigDecimal(5, linea.subtotal);
            statement.executeUpdate();

            return obtenerIdGenerado(statement, "id_reserva_por_venta");
        }
    }

    private static int insertarEntrada(Connection connection, int idReserva) throws SQLException {
        String sql = "INSERT INTO ENTRADA (id_reserva_por_venta, estado_entrada) VALUES (?, 'EMITIDA')";

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idReserva);
            statement.executeUpdate();

            return obtenerIdGenerado(statement, "id_entrada");
        }
    }

    private static void insertarCredencialQr(Connection connection, int idEntrada) throws SQLException {
        // La entrada nace con su credencial QR persistente (semilla); el token de 30s se calcula luego.
        String semilla = QrTokenService.nuevaSemilla();
        String sql = """
                INSERT INTO CREDENCIAL_QR (id_entrada, semilla_qr, nonce_qr, firma_digital)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setString(2, semilla);
            statement.setString(3, QrTokenService.nuevoNonce());
            statement.setString(4, QrTokenService.firma(idEntrada, semilla));
            statement.executeUpdate();
        }
    }

    private static void insertarMovimientoInicial(Connection connection, int idEntrada, int idComprador) throws SQLException {
        String sql = """
                INSERT INTO MOVIMIENTO_ASIGNACION_ENTRADA (
                    id_entrada,
                    nro_movimiento,
                    id_usuario_titular_origen,
                    tipo_movimiento,
                    estado_movimiento,
                    fecha_desde
                ) VALUES (?, 1, ?, 'COMPRA_INICIAL', 'CONFIRMADA', CURRENT_TIMESTAMP)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setInt(2, idComprador);
            statement.executeUpdate();
        }
    }

    private static void insertarIntentoPagoAprobado(
            Connection connection,
            int idVenta,
            BigDecimal montoTotal,
            String metodoPago) throws SQLException {

        String sql = """
                INSERT INTO INTENTO_PAGO (
                    id_venta,
                    monto_pago,
                    estado_pago,
                    referencia_pago,
                    metodo_pago
                ) VALUES (?, ?, 'APROBADO', ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.setBigDecimal(2, montoTotal);
            statement.setString(3, "SIMULADO-VENTA-" + idVenta);
            statement.setString(4, metodoPago);
            statement.executeUpdate();
        }
    }

    private static int obtenerIdGenerado(Statement statement, String nombreClave) throws SQLException {
        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
            if (!generatedKeys.next()) {
                throw new SQLException("No se obtuvo el " + nombreClave + " generado");
            }

            return generatedKeys.getInt(1);
        }
    }

    // --- Utilidades de respuesta ---

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String mensaje) {
        Map<String, Object> respuesta = base("ERROR", code);
        respuesta.put("message", mensaje);
        return ResponseEntity.status(status).body(respuesta);
    }

    private static Map<String, Object> base(String status, String code) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", status);
        respuesta.put("code", code);
        respuesta.put("timestamp", OffsetDateTime.now().toString());
        return respuesta;
    }

    private static void rollbackSilencioso(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // No se expone el detalle al cliente.
        }
    }

    // --- Tipos auxiliares ---

    public static class CompraRequest {
        public List<LineaCompra> lineas;
        public String metodoPago;
    }

    public static class LineaCompra {
        public Integer idEvento;
        public Integer idSector;
        public Integer cantidad;
    }

    private static class LineaNormalizada {
        final int idEvento;
        final int idSector;
        int cantidad;
        BigDecimal precioUnitario;
        BigDecimal subtotal;

        LineaNormalizada(int idEvento, int idSector, int cantidad) {
            this.idEvento = idEvento;
            this.idSector = idSector;
            this.cantidad = cantidad;
        }
    }

    private record EventoSector(BigDecimal precioEntrada, int capacidadHabilitada) {
    }

    private record TasaVigente(int idTasaComision, BigDecimal porcentaje) {
    }

    private static class CompraInvalidaException extends Exception {
        private final String code;

        CompraInvalidaException(String code, String mensaje) {
            super(mensaje);
            this.code = code;
        }

        String getCode() {
            return code;
        }
    }
}
