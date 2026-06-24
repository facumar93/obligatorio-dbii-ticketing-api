package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.QrTokenService.QrConfigurationException;
import uy.edu.ucu.ticketing.api.QrTokenService.QrTokenInvalidoException;
import uy.edu.ucu.ticketing.api.QrTokenService.TokenQrParseado;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class ValidacionController {

    private static final int MAX_CODIGO_QR = 255;
    private static final int SEGUNDOS_POR_VENTANA = 30;

    @GetMapping("/validacion/contexto")
    public ResponseEntity<Map<String, Object>> obtenerContexto(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            UsuarioActor actor = buscarUsuarioActor(connection, token.uid());

            if (actor == null) {
                return respuestaError(
                        HttpStatus.FORBIDDEN,
                        "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no existe en USUARIO_GENERAL"
                );
            }

            String legajo = buscarLegajoValidador(connection, actor.idUsuario());

            if (legajo == null) {
                return respuestaError(
                        HttpStatus.FORBIDDEN,
                        "USUARIO_NO_ES_VALIDADOR",
                        "El usuario autenticado no tiene rol de validacion"
                );
            }

            Map<String, Object> respuesta = respuestaBase("OK", "CONTEXTO_VALIDACION_OK");
            respuesta.put("idUsuario", actor.idUsuario());
            respuesta.put("nombre", actor.nombreCompleto());
            respuesta.put("numeroLegajo", legajo);
            respuesta.put(
                    "asignaciones",
                    buscarAsignacionesDisponibles(connection, actor.idUsuario())
            );
            return ResponseEntity.ok(respuesta);

        } catch (SQLException | IllegalStateException e) {
            return respuestaError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ERROR_DB_VALIDACION",
                    "No se pudo obtener el contexto del validador"
            );
        }
    }

    @PostMapping("/validaciones/ingreso")
    public ResponseEntity<Map<String, Object>> validarIngreso(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) ValidacionIngresoRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        ValidacionInput input = validarInput(request);

        if (input.error() != null) {
            return respuestaError(HttpStatus.BAD_REQUEST, "INPUT_INVALIDO", input.error());
        }


        try (Connection connection = DbConnectionFactory.getConnection()) {
            UsuarioActor actor = buscarUsuarioActor(connection, token.uid());

            if (actor == null) {
                return respuestaError(
                        HttpStatus.FORBIDDEN,
                        "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no existe en USUARIO_GENERAL"
                );
            }

            if (buscarLegajoValidador(connection, actor.idUsuario()) == null) {
                return respuestaError(
                        HttpStatus.FORBIDDEN,
                        "USUARIO_NO_ES_VALIDADOR",
                        "El usuario autenticado no tiene rol de validacion"
                );
            }

            if (!asignacionPerteneceAlActor(
                    connection,
                    input.idAsignacionDispositivoValidador(),
                    actor.idUsuario()
            )) {
                return respuestaError(
                        HttpStatus.FORBIDDEN,
                        "ASIGNACION_NO_AUTORIZADA",
                        "La asignacion indicada no pertenece al validador autenticado"
                );
            }

            QrTokenService qrTokenService;

            try {
                qrTokenService = QrTokenService.desdeEntorno();
            } catch (QrConfigurationException e) {
                return respuestaError(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "QR_SECRET_NO_CONFIGURADO",
                        e.getMessage()
                );
            }

            TokenQrParseado tokenQr;

            try {
                tokenQr = qrTokenService.parsearToken(input.codigoQr());
            } catch (QrTokenInvalidoException e) {
                return respuestaError(HttpStatus.BAD_REQUEST, "QR_INVALIDO", e.getMessage());
            }

            connection.setAutoCommit(false);

            try {
                EntradaParaValidar entrada = bloquearEntrada(connection, tokenQr.idEntrada());

                if (entrada == null) {
                    connection.rollback();
                    return respuestaError(
                            HttpStatus.NOT_FOUND,
                            "ENTRADA_NO_ENCONTRADA",
                            "El codigo QR refiere a una entrada inexistente"
                    );
                }

                AsignacionValidador asignacion = bloquearAsignacion(
                        connection,
                        input.idAsignacionDispositivoValidador()
                );

                if (asignacion == null
                        || asignacion.idUsuarioValidador() != actor.idUsuario()) {
                    connection.rollback();
                    return respuestaError(
                            HttpStatus.FORBIDDEN,
                            "ASIGNACION_NO_AUTORIZADA",
                            "La asignacion indicada no pertenece al validador autenticado"
                    );
                }

                CredencialQr credencial = buscarCredencialBloqueada(
                        connection,
                        entrada.idEntrada()
                );

                if (credencial == null) {
                    connection.rollback();
                    return respuestaError(
                            HttpStatus.CONFLICT,
                            "CREDENCIAL_QR_NO_ENCONTRADA",
                            "La entrada no tiene una credencial QR emitida"
                    );
                }

                if (!qrTokenService.validarFirmaCredencial(
                        entrada.idEntrada(),
                        credencial.semilla(),
                        credencial.nonce(),
                        credencial.firmaDigital()
                )) {
                    connection.rollback();
                    return respuestaError(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "CREDENCIAL_QR_INCONSISTENTE",
                            "La credencial QR persistida no supera su verificacion de integridad"
                    );
                }

                RelojBase reloj = obtenerRelojBase(connection);

                if (!qrTokenService.validarFirmaToken(
                        tokenQr,
                        credencial.semilla(),
                        credencial.nonce()
                )) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "QR_INVALIDO",
                            "El codigo QR fue alterado o no corresponde a la credencial",
                            "OTRO"
                    );
                }

                if (tokenQr.ventana() != reloj.ventanaActual()) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "QR_VENCIDO",
                            "El codigo QR ya no pertenece a la ventana vigente",
                            "QR_VENCIDO"
                    );
                }

                if ("CONSUMIDA".equals(entrada.estadoEntrada())) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "ENTRADA_CONSUMIDA",
                            "La entrada ya fue consumida",
                            "ENTRADA_CONSUMIDA"
                    );
                }

                if (!"EMITIDA".equals(entrada.estadoEntrada())) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "ENTRADA_NO_VALIDA",
                            "La entrada no se encuentra habilitada para ingresar",
                            "OTRO"
                    );
                }

                if (!asignacion.dispositivoYVinculacionActivos(reloj.ahora())) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "DISPOSITIVO_NO_AUTORIZADO",
                            "El dispositivo o su vinculacion no estan autorizados",
                            "DISPOSITIVO_NO_AUTORIZADO"
                    );
                }

                if (!asignacion.estaEnHorario(reloj.ahora())) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "FUERA_DE_HORARIO",
                            "La asignacion no esta activa en este momento",
                            "FUERA_DE_HORARIO"
                    );
                }

                if (asignacion.idEvento() != entrada.idEvento()
                        || asignacion.idSector() != entrada.idSector()) {
                    return rechazarConLectura(
                            connection,
                            entrada.idEntrada(),
                            asignacion.idAsignacion(),
                            input.codigoQr(),
                            reloj.ahora(),
                            "DISPOSITIVO_NO_AUTORIZADO",
                            "La asignacion no corresponde al evento y sector de la entrada",
                            "DISPOSITIVO_NO_AUTORIZADO"
                    );
                }

                consumirEntrada(connection, entrada.idEntrada());
                int idLectura = insertarLectura(
                        connection,
                        entrada.idEntrada(),
                        asignacion.idAsignacion(),
                        input.codigoQr(),
                        reloj.ahora(),
                        "ACEPTADA",
                        null
                );
                connection.commit();

                Map<String, Object> respuesta = respuestaBase(
                        "OK",
                        "VALIDACION_ACEPTADA"
                );
                respuesta.put("resultado", "ACEPTADA");
                respuesta.put("idLecturaValidacion", idLectura);
                respuesta.put("idEntrada", entrada.idEntrada());
                respuesta.put("message", "Ingreso validado. La entrada quedo consumida");
                return ResponseEntity.ok(respuesta);

            } catch (SQLException | IllegalStateException e) {
                rollbackSilencioso(connection);
                return respuestaError(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "ERROR_DB_VALIDACION",
                        "No se pudo completar la validacion de ingreso"
                );
            }

        } catch (SQLException | IllegalStateException e) {
            return respuestaError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ERROR_DB_VALIDACION",
                    "No se pudo completar la validacion de ingreso"
            );
        }
    }


    private static ValidacionInput validarInput(ValidacionIngresoRequest request) {
        if (request == null) {
            return ValidacionInput.error("El body de la validacion es obligatorio");
        }

        if (request.codigoQr == null || request.codigoQr.isBlank()) {
            return ValidacionInput.error("codigoQr es obligatorio");
        }

        String codigoQr = request.codigoQr.trim();

        if (codigoQr.length() > MAX_CODIGO_QR) {
            return ValidacionInput.error(
                    "codigoQr supera el maximo de " + MAX_CODIGO_QR + " caracteres"
            );
        }

        if (request.idAsignacionDispositivoValidador == null
                || request.idAsignacionDispositivoValidador <= 0) {
            return ValidacionInput.error(
                    "idAsignacionDispositivoValidador debe ser mayor a cero"
            );
        }

        return new ValidacionInput(
                null,
                codigoQr,
                request.idAsignacionDispositivoValidador
        );
    }

    private static UsuarioActor buscarUsuarioActor(Connection connection, String firebaseUid)
            throws SQLException {

        String sql = """
                SELECT id_usuario, nombre, apellido
                FROM USUARIO_GENERAL
                WHERE firebase_uid = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new UsuarioActor(
                        resultSet.getInt("id_usuario"),
                        resultSet.getString("nombre"),
                        resultSet.getString("apellido")
                );
            }
        }
    }

    private static String buscarLegajoValidador(Connection connection, int idUsuario)
            throws SQLException {

        String sql = """
                SELECT numero_legajo
                FROM USUARIO_DE_VALIDACION
                WHERE id_usuario = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("numero_legajo") : null;
            }
        }
    }

    private static List<Map<String, Object>> buscarAsignacionesDisponibles(
            Connection connection,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT
                    a.id_asignacion_dispositivo_validador,
                    d.id_dispositivo,
                    a.id_evento,
                    a.id_sector,
                    sl.nombre AS seleccion_local,
                    sv.nombre AS seleccion_visitante,
                    s.nombre_sector
                FROM ASIGNACION_DISPOSITIVO_VALIDADOR a
                JOIN VINCULACION_VALIDADOR_DISPOSITIVO v
                  ON v.id_vinculacion = a.id_vinculacion
                JOIN DISPOSITIVO_AUTORIZADO d
                  ON d.id_dispositivo = v.id_dispositivo
                JOIN EVENTO e
                  ON e.id_evento = a.id_evento
                JOIN SELECCION_NACIONAL sl
                  ON sl.id_seleccion = e.id_seleccion_local
                JOIN SELECCION_NACIONAL sv
                  ON sv.id_seleccion = e.id_seleccion_visitante
                JOIN SECTOR s
                  ON s.id_sector = a.id_sector
                WHERE v.id_usuario_validador = ?
                  AND d.estado = 'ACTIVO'
                  AND v.estado_vinculacion = 'ACTIVA'
                  AND v.fecha_desde <= NOW()
                  AND (v.fecha_hasta IS NULL OR v.fecha_hasta >= NOW())
                  AND a.fecha_cancelacion IS NULL
                  AND a.fecha_activacion <= NOW()
                  AND a.fecha_desactivacion >= NOW()
                ORDER BY a.id_asignacion_dispositivo_validador
                """;

        List<Map<String, Object>> asignaciones = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> asignacion = new LinkedHashMap<>();
                    asignacion.put(
                            "idAsignacionDispositivoValidador",
                            resultSet.getInt("id_asignacion_dispositivo_validador")
                    );
                    asignacion.put("idDispositivo", resultSet.getInt("id_dispositivo"));
                    asignacion.put("idEvento", resultSet.getInt("id_evento"));
                    asignacion.put("idSector", resultSet.getInt("id_sector"));
                    asignacion.put("seleccionLocal", resultSet.getString("seleccion_local"));
                    asignacion.put(
                            "seleccionVisitante",
                            resultSet.getString("seleccion_visitante")
                    );
                    asignacion.put("sector", resultSet.getString("nombre_sector"));
                    asignaciones.add(asignacion);
                }
            }
        }

        return asignaciones;
    }

    private static boolean asignacionPerteneceAlActor(
            Connection connection,
            int idAsignacion,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT 1
                FROM ASIGNACION_DISPOSITIVO_VALIDADOR a
                JOIN VINCULACION_VALIDADOR_DISPOSITIVO v
                  ON v.id_vinculacion = a.id_vinculacion
                WHERE a.id_asignacion_dispositivo_validador = ?
                  AND v.id_usuario_validador = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idAsignacion);
            statement.setInt(2, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static EntradaParaValidar bloquearEntrada(Connection connection, int idEntrada)
            throws SQLException {

        String sql = """
                SELECT
                    e.id_entrada,
                    e.estado_entrada,
                    r.id_evento,
                    r.id_sector
                FROM ENTRADA e
                JOIN RESERVA_POR_VENTA r
                  ON r.id_reserva_por_venta = e.id_reserva_por_venta
                WHERE e.id_entrada = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new EntradaParaValidar(
                        resultSet.getInt("id_entrada"),
                        resultSet.getString("estado_entrada"),
                        resultSet.getInt("id_evento"),
                        resultSet.getInt("id_sector")
                );
            }
        }
    }

    private static AsignacionValidador bloquearAsignacion(
            Connection connection,
            int idAsignacion
    ) throws SQLException {

        String sql = """
                SELECT
                    a.id_asignacion_dispositivo_validador,
                    a.id_evento,
                    a.id_sector,
                    a.fecha_activacion,
                    a.fecha_desactivacion,
                    a.fecha_cancelacion,
                    v.id_usuario_validador,
                    v.fecha_desde,
                    v.fecha_hasta,
                    v.estado_vinculacion,
                    d.id_dispositivo,
                    d.estado AS estado_dispositivo
                FROM ASIGNACION_DISPOSITIVO_VALIDADOR a
                JOIN VINCULACION_VALIDADOR_DISPOSITIVO v
                  ON v.id_vinculacion = a.id_vinculacion
                JOIN DISPOSITIVO_AUTORIZADO d
                  ON d.id_dispositivo = v.id_dispositivo
                WHERE a.id_asignacion_dispositivo_validador = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idAsignacion);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new AsignacionValidador(
                        resultSet.getInt("id_asignacion_dispositivo_validador"),
                        resultSet.getInt("id_usuario_validador"),
                        resultSet.getInt("id_dispositivo"),
                        resultSet.getString("estado_dispositivo"),
                        resultSet.getString("estado_vinculacion"),
                        resultSet.getTimestamp("fecha_desde"),
                        resultSet.getTimestamp("fecha_hasta"),
                        resultSet.getInt("id_evento"),
                        resultSet.getInt("id_sector"),
                        resultSet.getTimestamp("fecha_activacion"),
                        resultSet.getTimestamp("fecha_desactivacion"),
                        resultSet.getTimestamp("fecha_cancelacion")
                );
            }
        }
    }

    private static CredencialQr buscarCredencialBloqueada(
            Connection connection,
            int idEntrada
    ) throws SQLException {

        String sql = """
                SELECT semilla_qr, nonce_qr, firma_digital
                FROM CREDENCIAL_QR
                WHERE id_entrada = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new CredencialQr(
                        resultSet.getString("semilla_qr"),
                        resultSet.getString("nonce_qr"),
                        resultSet.getString("firma_digital")
                );
            }
        }
    }

    private static RelojBase obtenerRelojBase(Connection connection) throws SQLException {
        String sql = """
                SELECT
                    NOW() AS ahora,
                    FLOOR(UNIX_TIMESTAMP(NOW()) / ?) AS ventana_actual
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, SEGUNDOS_POR_VENTANA);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new RelojBase(
                        resultSet.getTimestamp("ahora"),
                        resultSet.getLong("ventana_actual")
                );
            }
        }
    }

    private static void consumirEntrada(Connection connection, int idEntrada)
            throws SQLException {

        String sql = """
                UPDATE ENTRADA
                SET estado_entrada = 'CONSUMIDA'
                WHERE id_entrada = ?
                  AND estado_entrada = 'EMITIDA'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            if (statement.executeUpdate() != 1) {
                throw new SQLException("No se pudo consumir la entrada bloqueada");
            }
        }
    }

    private static int insertarLectura(
            Connection connection,
            int idEntrada,
            int idAsignacion,
            String codigoQr,
            Timestamp fechaLectura,
            String resultado,
            String motivo
    ) throws SQLException {

        String sql = """
                INSERT INTO LECTURA_DE_VALIDACION_INGRESO (
                    id_entrada,
                    id_asignacion_dispositivo_validador,
                    codigo_qr_leido,
                    fecha_hora_lectura,
                    resultado_validacion,
                    motivo_rechazo
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setInt(1, idEntrada);
            statement.setInt(2, idAsignacion);
            statement.setString(3, codigoQr);
            statement.setTimestamp(4, fechaLectura);
            statement.setString(5, resultado);
            statement.setString(6, motivo);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("No se obtuvo el id de la lectura");
                }

                return generatedKeys.getInt(1);
            }
        }
    }

    private static ResponseEntity<Map<String, Object>> rechazarConLectura(
            Connection connection,
            int idEntrada,
            int idAsignacion,
            String codigoQr,
            Timestamp fechaLectura,
            String code,
            String message,
            String motivo
    ) throws SQLException {

        int idLectura = insertarLectura(
                connection,
                idEntrada,
                idAsignacion,
                codigoQr,
                fechaLectura,
                "RECHAZADA",
                motivo
        );
        connection.commit();

        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("resultado", "RECHAZADA");
        respuesta.put("idLecturaValidacion", idLectura);
        respuesta.put("idEntrada", idEntrada);
        respuesta.put("motivoRechazo", motivo);
        respuesta.put("message", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(
            String code,
            String message
    ) {

        return respuestaError(HttpStatus.UNAUTHORIZED, code, message);
    }

    private static ResponseEntity<Map<String, Object>> respuestaError(
            HttpStatus status,
            String code,
            String message
    ) {

        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", message);
        return ResponseEntity.status(status).body(respuesta);
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

    public static class ValidacionIngresoRequest {
        public String codigoQr;
        public Integer idAsignacionDispositivoValidador;
    }

    private record ValidacionInput(
            String error,
            String codigoQr,
            int idAsignacionDispositivoValidador
    ) {
        static ValidacionInput error(String message) {
            return new ValidacionInput(message, null, 0);
        }
    }

    private record UsuarioActor(int idUsuario, String nombre, String apellido) {
        String nombreCompleto() {
            return (nombre + " " + apellido).trim();
        }
    }

    private record EntradaParaValidar(
            int idEntrada,
            String estadoEntrada,
            int idEvento,
            int idSector
    ) {
    }

    private record CredencialQr(String semilla, String nonce, String firmaDigital) {
    }

    private record RelojBase(Timestamp ahora, long ventanaActual) {
    }

    private record AsignacionValidador(
            int idAsignacion,
            int idUsuarioValidador,
            int idDispositivo,
            String estadoDispositivo,
            String estadoVinculacion,
            Timestamp fechaDesdeVinculacion,
            Timestamp fechaHastaVinculacion,
            int idEvento,
            int idSector,
            Timestamp fechaActivacion,
            Timestamp fechaDesactivacion,
            Timestamp fechaCancelacion
    ) {

        boolean dispositivoYVinculacionActivos(Timestamp ahora) {
            if (!"ACTIVO".equals(estadoDispositivo)
                    || !"ACTIVA".equals(estadoVinculacion)
                    || fechaCancelacion != null
                    || fechaDesdeVinculacion.after(ahora)) {
                return false;
            }

            return fechaHastaVinculacion == null || !fechaHastaVinculacion.before(ahora);
        }

        boolean estaEnHorario(Timestamp ahora) {
            return !ahora.before(fechaActivacion) && !ahora.after(fechaDesactivacion);
        }
    }
}
