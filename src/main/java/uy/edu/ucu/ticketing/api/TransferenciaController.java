package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Transferencia directa de entradas (desacople compra/tenencia).
 *
 * Una transferencia nace PENDIENTE; al ser ACEPTADA por el destinatario cambia la
 * titularidad: se cierra el movimiento vigente anterior (fecha_hasta=now) y la fila
 * de transferencia aceptada pasa a ser el vigente. Reglas: max 3 aceptadas por
 * entrada, una sola PENDIENTE a la vez, evento no iniciado. Todo en transaccion con
 * FOR UPDATE sobre la cadena de movimientos de la entrada.
 */
@RestController
public class TransferenciaController {

    @PostMapping("/transferencias")
    public ResponseEntity<Map<String, Object>> iniciar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) IniciarRequest request) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        if (request == null || request.idEntrada == null || request.idEntrada <= 0
                || request.destinatarioCorreo == null || request.destinatarioCorreo.isBlank()) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS",
                    "Se requiere idEntrada y destinatarioCorreo");
        }
        int idEntrada = request.idEntrada;
        String correoDestino = request.destinatarioCorreo.trim();

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer solicitante = RoleGuard.resolveIdUsuario(connection, token.uid());
                if (solicitante == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                            "El usuario autenticado no esta registrado en el sistema");
                }

                bloquearCadena(connection, idEntrada);

                if (!TitularidadDao.esTitularVigente(connection, idEntrada, solicitante)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_TITULAR",
                            "No sos el titular vigente de esta entrada");
                }

                String estadoEntrada = estadoEntrada(connection, idEntrada);
                if (estadoEntrada == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.NOT_FOUND, "ENTRADA_INEXISTENTE", "La entrada no existe");
                }
                if (!"EMITIDA".equals(estadoEntrada)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "ENTRADA_NO_TRANSFERIBLE",
                            "La entrada no esta en estado transferible");
                }
                if (eventoYaInicio(connection, idEntrada)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "EVENTO_YA_INICIADO",
                            "No se puede transferir una entrada de un evento ya iniciado");
                }
                if (TitularidadDao.tienePendiente(connection, idEntrada)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "TRANSFERENCIA_PENDIENTE_EXISTENTE",
                            "Ya hay una transferencia pendiente sobre esta entrada");
                }
                if (TitularidadDao.transferenciasAceptadas(connection, idEntrada) >= 3) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "LIMITE_TRANSFERENCIAS",
                            "La entrada alcanzo el maximo de 3 transferencias");
                }

                Integer destinatario = idUsuarioPorCorreo(connection, correoDestino);
                if (destinatario == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.NOT_FOUND, "DESTINATARIO_INEXISTENTE",
                            "No existe un usuario con ese correo");
                }
                if (destinatario.intValue() == solicitante.intValue()) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.BAD_REQUEST, "DESTINATARIO_INVALIDO",
                            "No podes transferirte una entrada a vos mismo");
                }

                int nro = TitularidadDao.proximoNroMovimiento(connection, idEntrada);
                insertarTransferenciaPendiente(connection, idEntrada, nro, solicitante, destinatario);

                connection.commit();

                Map<String, Object> r = ApiResponses.base("OK", "TRANSFERENCIA_SOLICITADA");
                r.put("message", "Transferencia solicitada; espera la aceptacion del destinatario");
                r.put("idEntrada", idEntrada);
                r.put("nroMovimiento", nro);
                r.put("destinatario", correoDestino);
                return ResponseEntity.status(HttpStatus.CREATED).body(r);

            } catch (SQLException e) {
                ApiResponses.rollbackSilencioso(connection);
                return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_TRANSFERENCIA",
                        "No se pudo registrar la transferencia");
            }
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }

    @PostMapping("/transferencias/{idEntrada}/responder")
    public ResponseEntity<Map<String, Object>> responder(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada,
            @RequestBody(required = false) ResponderRequest request) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String decision = request == null || request.decision == null ? "" : request.decision.trim().toUpperCase();
        if (!"ACEPTAR".equals(decision) && !"RECHAZAR".equals(decision)) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "DECISION_INVALIDA",
                    "decision debe ser ACEPTAR o RECHAZAR");
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer responder = RoleGuard.resolveIdUsuario(connection, token.uid());
                if (responder == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                            "El usuario autenticado no esta registrado en el sistema");
                }

                bloquearCadena(connection, idEntrada);

                int[] pendiente = pendienteDe(connection, idEntrada); // [nro, destinatario] o null
                if (pendiente == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.NOT_FOUND, "TRANSFERENCIA_NO_PENDIENTE",
                            "No hay una transferencia pendiente para esta entrada");
                }
                int nro = pendiente[0];
                int destinatario = pendiente[1];
                if (destinatario != responder.intValue()) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_DESTINATARIO",
                            "Solo el destinatario puede responder esta transferencia");
                }

                if ("RECHAZAR".equals(decision)) {
                    actualizar(connection,
                            "UPDATE MOVIMIENTO_ASIGNACION_ENTRADA SET estado_movimiento='RECHAZADA', fecha_respuesta=CURRENT_TIMESTAMP "
                            + "WHERE id_entrada=? AND nro_movimiento=? AND estado_movimiento='PENDIENTE'",
                            idEntrada, nro);
                    connection.commit();
                    Map<String, Object> r = ApiResponses.base("OK", "TRANSFERENCIA_RECHAZADA");
                    r.put("message", "Transferencia rechazada");
                    r.put("idEntrada", idEntrada);
                    return ResponseEntity.ok(r);
                }

                // ACEPTAR: validaciones defensivas
                if (!"EMITIDA".equals(estadoEntrada(connection, idEntrada))) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "ENTRADA_NO_TRANSFERIBLE",
                            "La entrada ya no esta en estado transferible");
                }
                if (eventoYaInicio(connection, idEntrada)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "EVENTO_YA_INICIADO",
                            "El evento ya inicio");
                }
                if (TitularidadDao.transferenciasAceptadas(connection, idEntrada) >= 3) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "LIMITE_TRANSFERENCIAS",
                            "La entrada alcanzo el maximo de 3 transferencias");
                }

                // 1) Cerrar el vigente efectivo anterior (no toca la fila PENDIENTE).
                actualizar(connection,
                        "UPDATE MOVIMIENTO_ASIGNACION_ENTRADA m SET m.fecha_hasta=CURRENT_TIMESTAMP "
                        + "WHERE m.id_entrada=? AND " + TitularidadDao.COND_VIGENTE_EFECTIVO,
                        idEntrada);
                // 2) Aceptar la transferencia: pasa a ser el vigente.
                int filas = actualizar(connection,
                        "UPDATE MOVIMIENTO_ASIGNACION_ENTRADA SET estado_movimiento='ACEPTADA', "
                        + "fecha_respuesta=CURRENT_TIMESTAMP, fecha_desde=CURRENT_TIMESTAMP "
                        + "WHERE id_entrada=? AND nro_movimiento=? AND estado_movimiento='PENDIENTE'",
                        idEntrada, nro);
                if (filas != 1) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "CONFLICTO_TRANSFERENCIA",
                            "La transferencia ya no estaba disponible");
                }

                connection.commit();
                Map<String, Object> r = ApiResponses.base("OK", "TRANSFERENCIA_ACEPTADA");
                r.put("message", "Transferencia aceptada; ahora sos el titular de la entrada");
                r.put("idEntrada", idEntrada);
                return ResponseEntity.ok(r);

            } catch (SQLException e) {
                ApiResponses.rollbackSilencioso(connection);
                return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_TRANSFERENCIA",
                        "No se pudo responder la transferencia");
            }
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }

    @GetMapping("/transferencias/recibidas")
    public ResponseEntity<?> recibidas(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return listar(authorizationHeader, true);
    }

    @GetMapping("/transferencias/mias")
    public ResponseEntity<?> mias(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return listar(authorizationHeader, false);
    }

    private ResponseEntity<?> listar(String authorizationHeader, boolean recibidas) {
        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String filtro = recibidas
                ? "m.id_usuario_destinatario = ? AND m.estado_movimiento = 'PENDIENTE'"
                : "m.id_usuario_titular_origen = ? AND m.tipo_movimiento = 'TRANSFERENCIA'";

        String sql = """
                SELECT m.id_entrada, m.nro_movimiento, m.estado_movimiento, m.fecha_solicitud, m.fecha_respuesta,
                       uo.correo AS origen_correo, ud.correo AS dest_correo,
                       sl.nombre AS local, sv.nombre AS visitante, est.nombre AS estadio,
                       s.nombre_sector, ev.fecha_hora_inicio
                FROM MOVIMIENTO_ASIGNACION_ENTRADA m
                JOIN ENTRADA e ON e.id_entrada = m.id_entrada
                JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta
                JOIN SECTOR s ON s.id_sector = r.id_sector
                JOIN EVENTO ev ON ev.id_evento = r.id_evento
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                JOIN USUARIO_GENERAL uo ON uo.id_usuario = m.id_usuario_titular_origen
                LEFT JOIN USUARIO_GENERAL ud ON ud.id_usuario = m.id_usuario_destinatario
                WHERE """ + " " + filtro + " ORDER BY m.fecha_solicitud DESC";

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, idUsuario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("idEntrada", rs.getInt("id_entrada"));
                        m.put("nroMovimiento", rs.getInt("nro_movimiento"));
                        m.put("estado", rs.getString("estado_movimiento"));
                        m.put("local", rs.getString("local"));
                        m.put("visitante", rs.getString("visitante"));
                        m.put("estadio", rs.getString("estadio"));
                        m.put("nombreSector", rs.getString("nombre_sector"));
                        m.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                        m.put("origen", rs.getString("origen_correo"));
                        m.put("destinatario", rs.getString("dest_correo"));
                        m.put("fechaSolicitud", str(rs.getTimestamp("fecha_solicitud")));
                        m.put("fechaRespuesta", str(rs.getTimestamp("fecha_respuesta")));
                        out.add(m);
                    }
                }
            }
            return ResponseEntity.ok(out);
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_TRANSFERENCIAS",
                    "No se pudieron cargar las transferencias");
        }
    }

    // --- helpers ---

    private static void bloquearCadena(Connection connection, int idEntrada) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT nro_movimiento FROM MOVIMIENTO_ASIGNACION_ENTRADA WHERE id_entrada = ? FOR UPDATE")) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { /* lock */ } }
        }
    }

    private static String estadoEntrada(Connection connection, int idEntrada) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT estado_entrada FROM ENTRADA WHERE id_entrada = ?")) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static boolean eventoYaInicio(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT ev.fecha_hora_inicio
                FROM ENTRADA e
                JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta
                JOIN EVENTO ev ON ev.id_evento = r.id_evento
                WHERE e.id_entrada = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                java.sql.Timestamp inicio = rs.getTimestamp("fecha_hora_inicio");
                return inicio != null && !inicio.toLocalDateTime().isAfter(LocalDateTime.now());
            }
        }
    }

    private static Integer idUsuarioPorCorreo(Connection connection, String correo) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id_usuario FROM USUARIO_GENERAL WHERE correo = ?")) {
            ps.setString(1, correo);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    private static int[] pendienteDe(Connection connection, int idEntrada) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT nro_movimiento, id_usuario_destinatario FROM MOVIMIENTO_ASIGNACION_ENTRADA "
                + "WHERE id_entrada = ? AND tipo_movimiento = 'TRANSFERENCIA' AND estado_movimiento = 'PENDIENTE'")) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{ rs.getInt(1), rs.getInt(2) } : null;
            }
        }
    }

    private static void insertarTransferenciaPendiente(Connection connection, int idEntrada, int nro,
            int origen, int destinatario) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO MOVIMIENTO_ASIGNACION_ENTRADA "
                + "(id_entrada, nro_movimiento, id_usuario_titular_origen, id_usuario_destinatario, "
                + " tipo_movimiento, estado_movimiento, fecha_solicitud) "
                + "VALUES (?, ?, ?, ?, 'TRANSFERENCIA', 'PENDIENTE', CURRENT_TIMESTAMP)")) {
            ps.setInt(1, idEntrada);
            ps.setInt(2, nro);
            ps.setInt(3, origen);
            ps.setInt(4, destinatario);
            ps.executeUpdate();
        }
    }

    private static int actualizar(Connection connection, String sql, int... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setInt(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    private static String str(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }

    public static class IniciarRequest {
        public Integer idEntrada;
        public String destinatarioCorreo;
    }

    public static class ResponderRequest {
        public String decision; // ACEPTAR | RECHAZAR
    }
}
