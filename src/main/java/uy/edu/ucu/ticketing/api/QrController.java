package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.QrTokenService.CredencialGenerada;
import uy.edu.ucu.ticketing.api.QrTokenService.QrConfigurationException;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class QrController {

    private static final int SEGUNDOS_POR_VENTANA = 30;

    @GetMapping("/entradas/{idEntrada}/qr")
    public ResponseEntity<Map<String, Object>> generarTokenQr(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        if (idEntrada <= 0) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", "idEntrada debe ser mayor a cero");
            return ResponseEntity.badRequest().body(respuesta);
        }

        QrTokenService qrTokenService;

        try {
            qrTokenService = QrTokenService.desdeEntorno();
        } catch (QrConfigurationException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "QR_SECRET_NO_CONFIGURADO");
            respuesta.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idActor = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idActor == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                EntradaBloqueada entrada = bloquearEntrada(connection, idEntrada);

                if (entrada == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_ENCONTRADA");
                    respuesta.put("message", "La entrada solicitada no existe");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (!"EMITIDA".equals(entrada.estadoEntrada())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_EMITIDA");
                    respuesta.put("message", "Solo las entradas EMITIDA pueden generar un codigo QR");
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                List<MovimientoVigente> movimientos = buscarMovimientosVigentesBloqueados(
                        connection,
                        idEntrada
                );

                if (movimientos.size() != 1) {
                    connection.rollback();
                    return respuestaTitularidadInconsistente();
                }

                if (movimientos.get(0).idTitularActual() != idActor) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "ENTRADA_NO_PERTENECE_AL_USUARIO"
                    );
                    respuesta.put("message", "El usuario autenticado no es el titular vigente de la entrada");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                CredencialQr credencial = buscarCredencialBloqueada(connection, idEntrada);

                if (credencial == null) {
                    CredencialGenerada generada = qrTokenService.generarCredencial(idEntrada);
                    insertarCredencial(connection, idEntrada, generada);
                    credencial = new CredencialQr(
                            generada.semilla(),
                            generada.nonce(),
                            generada.firmaDigital()
                    );
                } else if (!qrTokenService.validarFirmaCredencial(
                        idEntrada,
                        credencial.semilla(),
                        credencial.nonce(),
                        credencial.firmaDigital()
                )) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "CREDENCIAL_QR_INCONSISTENTE"
                    );
                    respuesta.put("message", "La credencial QR persistida no supera su verificacion de integridad");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
                }

                VentanaQr ventana = obtenerVentanaBase(connection);
                String codigoQr = qrTokenService.generarToken(
                        idEntrada,
                        ventana.numero(),
                        credencial.semilla(),
                        credencial.nonce()
                );

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "QR_TOKEN_GENERADO");
                respuesta.put("idEntrada", idEntrada);
                respuesta.put("codigoQr", codigoQr);
                respuesta.put("ventanaSegundos", SEGUNDOS_POR_VENTANA);
                respuesta.put("generadoEn", timestampAString(ventana.generadoEn()));
                respuesta.put("expiraEn", timestampAString(ventana.expiraEn()));
                return ResponseEntity.ok(respuesta);

            } catch (SQLException | IllegalStateException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_QR");
                respuesta.put("message", "No se pudo generar el token QR");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_QR");
            respuesta.put("message", "No se pudo generar el token QR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static Integer buscarIdUsuarioPorFirebaseUid(Connection connection, String firebaseUid)
            throws SQLException {

        String sql = "SELECT id_usuario FROM USUARIO_GENERAL WHERE firebase_uid = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("id_usuario") : null;
            }
        }
    }

    private static EntradaBloqueada bloquearEntrada(Connection connection, int idEntrada)
            throws SQLException {

        String sql = """
                SELECT id_entrada, estado_entrada
                FROM ENTRADA
                WHERE id_entrada = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new EntradaBloqueada(
                        resultSet.getInt("id_entrada"),
                        resultSet.getString("estado_entrada")
                );
            }
        }
    }

    private static List<MovimientoVigente> buscarMovimientosVigentesBloqueados(
            Connection connection,
            int idEntrada
    ) throws SQLException {

        String sql = """
                SELECT tipo_movimiento, id_usuario_titular_origen, id_usuario_destinatario
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND fecha_desde IS NOT NULL
                  AND fecha_hasta IS NULL
                  AND (
                      (tipo_movimiento = 'COMPRA_INICIAL' AND estado_movimiento = 'CONFIRMADA')
                      OR
                      (tipo_movimiento = 'TRANSFERENCIA' AND estado_movimiento = 'ACEPTADA')
                  )
                ORDER BY nro_movimiento
                FOR UPDATE
                """;

        List<MovimientoVigente> movimientos = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tipoMovimiento = resultSet.getString("tipo_movimiento");
                    int idTitularActual = "COMPRA_INICIAL".equals(tipoMovimiento)
                            ? resultSet.getInt("id_usuario_titular_origen")
                            : resultSet.getInt("id_usuario_destinatario");

                    movimientos.add(new MovimientoVigente(idTitularActual));
                }
            }
        }

        return movimientos;
    }

    private static CredencialQr buscarCredencialBloqueada(Connection connection, int idEntrada)
            throws SQLException {

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

    private static void insertarCredencial(
            Connection connection,
            int idEntrada,
            CredencialGenerada credencial
    ) throws SQLException {

        String sql = """
                INSERT INTO CREDENCIAL_QR (
                    id_entrada,
                    semilla_qr,
                    nonce_qr,
                    firma_digital
                ) VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setString(2, credencial.semilla());
            statement.setString(3, credencial.nonce());
            statement.setString(4, credencial.firmaDigital());
            statement.executeUpdate();
        }
    }

    private static VentanaQr obtenerVentanaBase(Connection connection) throws SQLException {
        String sql = """
                SELECT
                    NOW() AS generado_en,
                    FLOOR(UNIX_TIMESTAMP(NOW()) / ?) AS numero_ventana,
                    FROM_UNIXTIME(
                        (FLOOR(UNIX_TIMESTAMP(NOW()) / ?) + 1) * ?
                    ) AS expira_en
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, SEGUNDOS_POR_VENTANA);
            statement.setInt(2, SEGUNDOS_POR_VENTANA);
            statement.setInt(3, SEGUNDOS_POR_VENTANA);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new VentanaQr(
                        resultSet.getLong("numero_ventana"),
                        resultSet.getTimestamp("generado_en"),
                        resultSet.getTimestamp("expira_en")
                );
            }
        }
    }

    private static ResponseEntity<Map<String, Object>> respuestaTitularidadInconsistente() {
        Map<String, Object> respuesta = respuestaBase(
                "ERROR",
                "ESTADO_TITULARIDAD_INCONSISTENTE"
        );
        respuesta.put("message", "No se pudo determinar un unico titular vigente para la entrada");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(
            String code,
            String message
    ) {

        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(respuesta);
    }

    private static Map<String, Object> respuestaBase(String status, String code) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", status);
        respuesta.put("code", code);
        respuesta.put("timestamp", OffsetDateTime.now().toString());
        return respuesta;
    }

    private static String timestampAString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private static void rollbackSilencioso(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // No se expone el detalle al cliente.
        }
    }

    private record EntradaBloqueada(int idEntrada, String estadoEntrada) {
    }

    private record MovimientoVigente(int idTitularActual) {
    }

    private record CredencialQr(String semilla, String nonce, String firmaDigital) {
    }

    private record VentanaQr(long numero, Timestamp generadoEn, Timestamp expiraEn) {
    }
}
