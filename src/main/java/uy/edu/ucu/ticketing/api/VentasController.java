package uy.edu.ucu.ticketing.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class VentasController {

    private static final int MAXIMO_ENTRADAS_POR_VENTA = 5;
    private static final int MINUTOS_EXPIRACION_VENTA = 15;
    private static final int ESCALA_DINERO = 2;
    private static final int MAXIMO_LARGO_REFERENCIA_PAGO = 100;

    @PostMapping("/ventas")
    public ResponseEntity<Map<String, Object>> crearVentaPendiente(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) CrearVentaRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        String errorInput = validarInput(request);

        if (errorInput != null) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", errorInput);

            return ResponseEntity.badRequest().body(respuesta);
        }

        int totalEntradas = request.lineas.stream()
                .mapToInt(linea -> linea.cantidad)
                .sum();

        if (totalEntradas > MAXIMO_ENTRADAS_POR_VENTA) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "MAXIMO_5_POR_VENTA");
            respuesta.put("message", "La venta no puede superar 5 entradas en total");

            return ResponseEntity.badRequest().body(respuesta);
        }

        List<LineaVentaRequest> lineasOrdenadas = request.lineas.stream()
                .sorted(Comparator.comparingInt(linea -> linea.idSector))
                .toList();

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idComprador = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idComprador == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                if (!existeEventoFuturo(connection, request.idEvento)) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "EVENTO_NO_DISPONIBLE");
                    respuesta.put("message", "El evento no existe o ya no esta disponible para la venta");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                List<LineaVentaCalculada> lineasCalculadas = new ArrayList<>();
                BigDecimal montoBase = BigDecimal.ZERO.setScale(ESCALA_DINERO, RoundingMode.HALF_UP);

                for (LineaVentaRequest linea : lineasOrdenadas) {
                    EventoSectorBloqueado eventoSector = bloquearEventoSector(
                            connection,
                            request.idEvento,
                            linea.idSector
                    );

                    if (eventoSector == null) {
                        connection.rollback();

                        Map<String, Object> respuesta = respuestaBase("ERROR", "SECTOR_NO_HABILITADO");
                        respuesta.put("message", "El sector no esta habilitado para este evento");
                        respuesta.put("idSector", linea.idSector);

                        return ResponseEntity.badRequest().body(respuesta);
                    }

                    int cantidadOcupada = calcularCantidadOcupada(
                            connection,
                            request.idEvento,
                            linea.idSector
                    );
                    int cupoDisponible = eventoSector.capacidadHabilitada() - cantidadOcupada;

                    if (linea.cantidad > cupoDisponible) {
                        connection.rollback();

                        Map<String, Object> respuesta = respuestaBase("ERROR", "SIN_CUPO");
                        respuesta.put("message", "No hay cupo suficiente para el sector solicitado");
                        respuesta.put("idSector", linea.idSector);
                        respuesta.put("cantidadSolicitada", linea.cantidad);
                        respuesta.put("cupoDisponible", cupoDisponible);

                        return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                    }

                    BigDecimal subtotal = eventoSector.precioEntrada()
                            .multiply(BigDecimal.valueOf(linea.cantidad))
                            .setScale(ESCALA_DINERO, RoundingMode.HALF_UP);

                    lineasCalculadas.add(new LineaVentaCalculada(
                            linea.idSector,
                            linea.cantidad,
                            subtotal
                    ));

                    montoBase = montoBase.add(subtotal).setScale(ESCALA_DINERO, RoundingMode.HALF_UP);
                }

                TasaVigente tasa = buscarTasaVigente(connection);

                if (tasa == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TASA_NO_VIGENTE");
                    respuesta.put("message", "No existe una tasa de comision vigente");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                BigDecimal comision = montoBase
                        .multiply(tasa.porcentaje())
                        .divide(BigDecimal.valueOf(100), ESCALA_DINERO, RoundingMode.HALF_UP);
                BigDecimal montoTotal = montoBase.add(comision).setScale(ESCALA_DINERO, RoundingMode.HALF_UP);

                int idVenta = insertarVenta(
                        connection,
                        idComprador,
                        tasa.idTasaComision(),
                        montoBase,
                        montoTotal
                );

                Timestamp fechaExpiracion = buscarFechaExpiracion(connection, idVenta);

                for (LineaVentaCalculada linea : lineasCalculadas) {
                    insertarReserva(connection, idVenta, request.idEvento, linea);
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "VENTA_PENDIENTE_CREADA");
                respuesta.put("idVenta", idVenta);
                respuesta.put("estado", "PENDIENTE");
                respuesta.put("montoTotal", montoTotal);
                respuesta.put("fechaExpiracion", timestampAString(fechaExpiracion));

                return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_CREAR_VENTA");
                respuesta.put("message", "No se pudo crear la venta pendiente");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_CONNECTION");
            respuesta.put("message", "No se pudo abrir la conexion a la base de datos");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @PostMapping("/ventas/{idVenta}/pagar")
    public ResponseEntity<Map<String, Object>> pagarVenta(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idVenta") int idVenta,
            @RequestBody(required = false) PagarVentaRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        String errorInput = validarInputPago(idVenta, request);

        if (errorInput != null) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", errorInput);

            return ResponseEntity.badRequest().body(respuesta);
        }

        String metodoPago = normalizarCodigo(request.metodoPago);
        String resultadoPasarela = simularResultadoPago(request.resultadoSimulado);
        String referenciaPago = normalizarReferenciaPago(request.referenciaPago);

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idComprador = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idComprador == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                VentaParaPago venta = bloquearVentaParaPago(connection, idVenta);

                if (venta == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "VENTA_NO_ENCONTRADA");
                    respuesta.put("message", "La venta no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (venta.idUsuarioComprador() != idComprador) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "VENTA_NO_AUTORIZADA");
                    respuesta.put("message", "La venta no pertenece al usuario autenticado");

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                if (!"PENDIENTE".equals(venta.estadoVenta())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "VENTA_NO_PENDIENTE");
                    respuesta.put("message", "La venta no esta pendiente de pago");
                    respuesta.put("estadoVenta", venta.estadoVenta());

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (venta.vencida()) {
                    cancelarVentaExpirada(connection, idVenta);
                    expirarReservas(connection, idVenta);
                    connection.commit();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "VENTA_EXPIRADA");
                    respuesta.put("message", "La venta expiro y sus reservas fueron liberadas");
                    respuesta.put("idVenta", idVenta);
                    respuesta.put("estadoVenta", "CANCELADA");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                int idIntentoPago = insertarIntentoPago(
                        connection,
                        idVenta,
                        venta.montoTotal(),
                        resultadoPasarela,
                        referenciaPago,
                        metodoPago
                );

                if ("RECHAZADO".equals(resultadoPasarela)) {
                    connection.commit();

                    Map<String, Object> respuesta = respuestaBase("OK", "PAGO_RECHAZADO");
                    respuesta.put("idVenta", idVenta);
                    respuesta.put("idIntentoPago", idIntentoPago);
                    respuesta.put("estadoVenta", "PENDIENTE");
                    respuesta.put("estadoPago", "RECHAZADO");

                    return ResponseEntity.ok(respuesta);
                }

                actualizarVentaPaga(connection, idVenta);

                List<ReservaParaEmitir> reservas = buscarReservasParaEmitir(connection, idVenta);

                if (reservas.isEmpty()) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "VENTA_SIN_RESERVAS");
                    respuesta.put("message", "La venta no tiene reservas emitibles");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                marcarReservasEmitidas(connection, idVenta);

                int entradasEmitidas = 0;

                for (ReservaParaEmitir reserva : reservas) {
                    for (int i = 0; i < reserva.cantidad(); i++) {
                        int idEntrada = insertarEntrada(connection, reserva.idReservaPorVenta());
                        insertarMovimientoCompraInicial(connection, idEntrada, idComprador);
                        entradasEmitidas++;
                    }
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "PAGO_APROBADO");
                respuesta.put("idVenta", idVenta);
                respuesta.put("idIntentoPago", idIntentoPago);
                respuesta.put("estadoVenta", "PAGA");
                respuesta.put("estadoPago", "APROBADO");
                respuesta.put("entradasEmitidas", entradasEmitidas);

                return ResponseEntity.ok(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_PAGAR_VENTA");
                respuesta.put("message", "No se pudo procesar el pago de la venta");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_CONNECTION");
            respuesta.put("message", "No se pudo abrir la conexion a la base de datos");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static String validarInput(CrearVentaRequest request) {
        if (request == null) {
            return "El body de la venta es obligatorio";
        }

        if (request.idEvento == null || request.idEvento <= 0) {
            return "idEvento debe ser mayor a cero";
        }

        if (request.lineas == null || request.lineas.isEmpty()) {
            return "La venta debe incluir al menos una linea";
        }

        Set<Integer> sectores = new HashSet<>();

        for (LineaVentaRequest linea : request.lineas) {
            if (linea == null) {
                return "Las lineas de venta no pueden ser nulas";
            }

            if (linea.idSector == null || linea.idSector <= 0) {
                return "idSector debe ser mayor a cero";
            }

            if (linea.cantidad == null || linea.cantidad <= 0) {
                return "cantidad debe ser mayor a cero";
            }

            if (!sectores.add(linea.idSector)) {
                return "No se puede repetir el mismo sector en una venta";
            }
        }

        return null;
    }

    private static String validarInputPago(int idVenta, PagarVentaRequest request) {
        if (idVenta <= 0) {
            return "idVenta debe ser mayor a cero";
        }

        if (request == null) {
            return "El body del pago es obligatorio";
        }

        String metodoPago = normalizarCodigo(request.metodoPago);

        if (!esMetodoPagoValido(metodoPago)) {
            return "metodoPago debe ser TARJETA_CREDITO, TARJETA_DEBITO o TRANSFERENCIA";
        }

        String resultadoSimulado = normalizarCodigo(request.resultadoSimulado);

        if (!esResultadoPagoValido(resultadoSimulado)) {
            return "resultadoSimulado debe ser APROBADO o RECHAZADO";
        }

        String referenciaPago = normalizarReferenciaPago(request.referenciaPago);

        if (referenciaPago != null && referenciaPago.length() > MAXIMO_LARGO_REFERENCIA_PAGO) {
            return "referenciaPago no puede superar 100 caracteres";
        }

        return null;
    }

    private static boolean esMetodoPagoValido(String metodoPago) {
        return "TARJETA_CREDITO".equals(metodoPago)
                || "TARJETA_DEBITO".equals(metodoPago)
                || "TRANSFERENCIA".equals(metodoPago);
    }

    private static boolean esResultadoPagoValido(String resultadoPago) {
        return "APROBADO".equals(resultadoPago) || "RECHAZADO".equals(resultadoPago);
    }

    private static String normalizarCodigo(String valor) {
        if (valor == null) {
            return "";
        }

        return valor.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizarReferenciaPago(String referenciaPago) {
        if (referenciaPago == null) {
            return null;
        }

        String referenciaNormalizada = referenciaPago.trim();

        if (referenciaNormalizada.isEmpty()) {
            return null;
        }

        return referenciaNormalizada;
    }

    private static String simularResultadoPago(String resultadoSimulado) {
        return normalizarCodigo(resultadoSimulado);
    }

    private static Integer buscarIdUsuarioPorFirebaseUid(Connection connection, String firebaseUid) throws SQLException {
        String sql = """
                SELECT id_usuario
                FROM USUARIO_GENERAL
                WHERE firebase_uid = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getInt("id_usuario");
            }
        }
    }

    private static boolean existeEventoFuturo(Connection connection, int idEvento) throws SQLException {
        String sql = """
                SELECT id_evento
                FROM EVENTO
                WHERE id_evento = ?
                  AND fecha_hora_inicio > NOW()
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static EventoSectorBloqueado bloquearEventoSector(
            Connection connection,
            int idEvento,
            int idSector
    ) throws SQLException {

        String sql = """
                SELECT
                    id_sector,
                    precio_entrada,
                    capacidad_habilitada
                FROM EVENTO_SECTOR
                WHERE id_evento = ?
                  AND id_sector = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);
            statement.setInt(2, idSector);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new EventoSectorBloqueado(
                        resultSet.getInt("id_sector"),
                        resultSet.getBigDecimal("precio_entrada"),
                        resultSet.getInt("capacidad_habilitada")
                );
            }
        }
    }

    private static int calcularCantidadOcupada(Connection connection, int idEvento, int idSector)
            throws SQLException {

        String sql = """
                SELECT COALESCE(SUM(rpv.cantidad), 0) AS cantidad_ocupada
                FROM RESERVA_POR_VENTA rpv
                JOIN VENTA v
                    ON v.id_venta = rpv.id_venta
                WHERE rpv.id_evento = ?
                  AND rpv.id_sector = ?
                  AND (
                      rpv.estado_reserva = 'EMITIDA'
                      OR (
                          rpv.estado_reserva = 'RESERVADA'
                          AND v.estado_venta = 'PENDIENTE'
                          AND v.fecha_expiracion > NOW()
                      )
                  )
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);
            statement.setInt(2, idSector);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("cantidad_ocupada");
            }
        }
    }

    private static TasaVigente buscarTasaVigente(Connection connection) throws SQLException {
        String sql = """
                SELECT id_tasa_comision, porcentaje
                FROM TASA
                WHERE fecha_hasta IS NULL
                  AND fecha_desde <= NOW()
                ORDER BY fecha_desde DESC, id_tasa_comision DESC
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            if (!resultSet.next()) {
                return null;
            }

            return new TasaVigente(
                    resultSet.getInt("id_tasa_comision"),
                    resultSet.getBigDecimal("porcentaje")
            );
        }
    }

    private static int insertarVenta(
            Connection connection,
            int idComprador,
            int idTasaComision,
            BigDecimal montoBase,
            BigDecimal montoTotal
    ) throws SQLException {

        String sql = """
                INSERT INTO VENTA (
                    id_usuario_comprador,
                    id_tasa_comision,
                    fecha_venta,
                    fecha_expiracion,
                    estado_venta,
                    monto_base,
                    monto_total
                )
                VALUES (
                    ?,
                    ?,
                    NOW(),
                    DATE_ADD(NOW(), INTERVAL %d MINUTE),
                    'PENDIENTE',
                    ?,
                    ?
                )
                """.formatted(MINUTOS_EXPIRACION_VENTA);

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idComprador);
            statement.setInt(2, idTasaComision);
            statement.setBigDecimal(3, montoBase);
            statement.setBigDecimal(4, montoTotal);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("No se pudo obtener el id generado de la venta");
                }

                return generatedKeys.getInt(1);
            }
        }
    }

    private static Timestamp buscarFechaExpiracion(Connection connection, int idVenta) throws SQLException {
        String sql = """
                SELECT fecha_expiracion
                FROM VENTA
                WHERE id_venta = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("No se pudo leer fecha_expiracion de la venta");
                }

                return resultSet.getTimestamp("fecha_expiracion");
            }
        }
    }

    private static void insertarReserva(
            Connection connection,
            int idVenta,
            int idEvento,
            LineaVentaCalculada linea
    ) throws SQLException {

        String sql = """
                INSERT INTO RESERVA_POR_VENTA (
                    id_venta,
                    id_evento,
                    id_sector,
                    cantidad,
                    subtotal,
                    estado_reserva
                )
                VALUES (?, ?, ?, ?, ?, 'RESERVADA')
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.setInt(2, idEvento);
            statement.setInt(3, linea.idSector());
            statement.setInt(4, linea.cantidad());
            statement.setBigDecimal(5, linea.subtotal());
            statement.executeUpdate();
        }
    }

    private static VentaParaPago bloquearVentaParaPago(Connection connection, int idVenta) throws SQLException {
        String sql = """
                SELECT
                    id_venta,
                    id_usuario_comprador,
                    estado_venta,
                    fecha_expiracion,
                    monto_total,
                    CASE
                        WHEN fecha_expiracion <= NOW() THEN 1
                        ELSE 0
                    END AS vencida
                FROM VENTA
                WHERE id_venta = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new VentaParaPago(
                        resultSet.getInt("id_venta"),
                        resultSet.getInt("id_usuario_comprador"),
                        resultSet.getString("estado_venta"),
                        resultSet.getTimestamp("fecha_expiracion"),
                        resultSet.getBigDecimal("monto_total"),
                        resultSet.getInt("vencida") == 1
                );
            }
        }
    }

    private static void cancelarVentaExpirada(Connection connection, int idVenta) throws SQLException {
        String sql = """
                UPDATE VENTA
                SET estado_venta = 'CANCELADA'
                WHERE id_venta = ?
                  AND estado_venta = 'PENDIENTE'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.executeUpdate();
        }
    }

    private static void expirarReservas(Connection connection, int idVenta) throws SQLException {
        String sql = """
                UPDATE RESERVA_POR_VENTA
                SET estado_reserva = 'EXPIRADA'
                WHERE id_venta = ?
                  AND estado_reserva = 'RESERVADA'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.executeUpdate();
        }
    }

    private static int insertarIntentoPago(
            Connection connection,
            int idVenta,
            BigDecimal montoPago,
            String estadoPago,
            String referenciaPago,
            String metodoPago
    ) throws SQLException {

        String sql = """
                INSERT INTO INTENTO_PAGO (
                    id_venta,
                    fecha_hora_pago,
                    monto_pago,
                    estado_pago,
                    referencia_pago,
                    metodo_pago
                )
                VALUES (?, NOW(), ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idVenta);
            statement.setBigDecimal(2, montoPago);
            statement.setString(3, estadoPago);
            statement.setString(4, referenciaPago);
            statement.setString(5, metodoPago);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("No se pudo obtener el id generado del intento de pago");
                }

                return generatedKeys.getInt(1);
            }
        }
    }

    private static void actualizarVentaPaga(Connection connection, int idVenta) throws SQLException {
        String sql = """
                UPDATE VENTA
                SET estado_venta = 'PAGA'
                WHERE id_venta = ?
                  AND estado_venta = 'PENDIENTE'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.executeUpdate();
        }
    }

    private static List<ReservaParaEmitir> buscarReservasParaEmitir(Connection connection, int idVenta)
            throws SQLException {

        String sql = """
                SELECT id_reserva_por_venta, cantidad
                FROM RESERVA_POR_VENTA
                WHERE id_venta = ?
                  AND estado_reserva = 'RESERVADA'
                ORDER BY id_reserva_por_venta
                FOR UPDATE
                """;

        List<ReservaParaEmitir> reservas = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reservas.add(new ReservaParaEmitir(
                            resultSet.getInt("id_reserva_por_venta"),
                            resultSet.getInt("cantidad")
                    ));
                }
            }
        }

        return reservas;
    }

    private static void marcarReservasEmitidas(Connection connection, int idVenta) throws SQLException {
        String sql = """
                UPDATE RESERVA_POR_VENTA
                SET estado_reserva = 'EMITIDA'
                WHERE id_venta = ?
                  AND estado_reserva = 'RESERVADA'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idVenta);
            statement.executeUpdate();
        }
    }

    private static int insertarEntrada(Connection connection, int idReservaPorVenta) throws SQLException {
        String sql = """
                INSERT INTO ENTRADA (
                    id_reserva_por_venta,
                    estado_entrada,
                    fecha_emision
                )
                VALUES (?, 'EMITIDA', NOW())
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idReservaPorVenta);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("No se pudo obtener el id generado de la entrada");
                }

                return generatedKeys.getInt(1);
            }
        }
    }

    private static void insertarMovimientoCompraInicial(
            Connection connection,
            int idEntrada,
            int idComprador
    ) throws SQLException {

        String sql = """
                INSERT INTO MOVIMIENTO_ASIGNACION_ENTRADA (
                    id_entrada,
                    nro_movimiento,
                    id_usuario_titular_origen,
                    id_usuario_destinatario,
                    tipo_movimiento,
                    estado_movimiento,
                    fecha_solicitud,
                    fecha_respuesta,
                    fecha_desde,
                    fecha_hasta
                )
                VALUES (?, 1, ?, NULL, 'COMPRA_INICIAL', 'CONFIRMADA', NOW(), NOW(), NOW(), NULL)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setInt(2, idComprador);
            statement.executeUpdate();
        }
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(String code, String mensaje) {
        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", mensaje);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(respuesta);
    }

    private static Map<String, Object> respuestaBase(String status, String code) {
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

    private static String timestampAString(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toLocalDateTime().toString();
    }

    public static class CrearVentaRequest {
        public Integer idEvento;
        public List<LineaVentaRequest> lineas;
    }

    public static class LineaVentaRequest {
        public Integer idSector;
        public Integer cantidad;
    }

    public static class PagarVentaRequest {
        public String metodoPago;
        public String referenciaPago;
        public String resultadoSimulado;
    }

    private record EventoSectorBloqueado(
            int idSector,
            BigDecimal precioEntrada,
            int capacidadHabilitada
    ) {
    }

    private record TasaVigente(
            int idTasaComision,
            BigDecimal porcentaje
    ) {
    }

    private record LineaVentaCalculada(
            int idSector,
            int cantidad,
            BigDecimal subtotal
    ) {
    }

    private record VentaParaPago(
            int idVenta,
            int idUsuarioComprador,
            String estadoVenta,
            Timestamp fechaExpiracion,
            BigDecimal montoTotal,
            boolean vencida
    ) {
    }

    private record ReservaParaEmitir(
            int idReservaPorVenta,
            int cantidad
    ) {
    }
}
