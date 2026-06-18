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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Validacion de ingreso a eventos. El funcionario opera una asignacion
 * dispositivo-validador concreta y escanea el QR dinamico. Cada lectura (aceptada
 * o rechazada) queda auditada en LECTURA_DE_VALIDACION_INGRESO; una entrada valida
 * pasa a CONSUMIDA de forma irreversible, en la misma transaccion.
 */
@RestController
public class ValidacionController {

    private static final int TOLERANCIA_VENTANAS = 1; // +-30s

    @GetMapping("/validacion/mis-asignaciones")
    public ResponseEntity<?> misAsignaciones(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String sql = """
                SELECT adv.id_asignacion_dispositivo_validador, adv.id_evento, adv.id_sector,
                       sl.nombre AS local, sv.nombre AS visitante, est.nombre AS estadio,
                       s.nombre_sector, ev.fecha_hora_inicio, ev.fecha_hora_fin
                FROM ASIGNACION_DISPOSITIVO_VALIDADOR adv
                JOIN VINCULACION_VALIDADOR_DISPOSITIVO v ON v.id_vinculacion = adv.id_vinculacion
                JOIN EVENTO ev ON ev.id_evento = adv.id_evento
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                JOIN SECTOR s ON s.id_sector = adv.id_sector
                WHERE v.id_usuario_validador = ?
                  AND v.estado_vinculacion = 'ACTIVA'
                  AND adv.fecha_cancelacion IS NULL
                ORDER BY ev.fecha_hora_inicio
                """;

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO", "El usuario no esta registrado");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, idUsuario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("idAsignacionDispositivoValidador", rs.getInt("id_asignacion_dispositivo_validador"));
                        m.put("idEvento", rs.getInt("id_evento"));
                        m.put("idSector", rs.getInt("id_sector"));
                        m.put("local", rs.getString("local"));
                        m.put("visitante", rs.getString("visitante"));
                        m.put("estadio", rs.getString("estadio"));
                        m.put("nombreSector", rs.getString("nombre_sector"));
                        m.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                        out.add(m);
                    }
                }
            }
            return ResponseEntity.ok(out);
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ASIGNACIONES",
                    "No se pudieron cargar las asignaciones");
        }
    }

    @PostMapping("/validacion/validar")
    public ResponseEntity<Map<String, Object>> validar(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) ValidarRequest req) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        if (req == null || req.idAsignacionDispositivoValidador == null
                || req.codigoQrLeido == null || req.codigoQrLeido.isBlank()) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS",
                    "Se requiere idAsignacionDispositivoValidador y codigoQrLeido");
        }
        int idAsignacion = req.idAsignacionDispositivoValidador;
        String codigo = req.codigoQrLeido.trim();

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer idValidador = RoleGuard.resolveIdUsuario(connection, token.uid());
                if (idValidador == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO", "El usuario no esta registrado");
                }
                if (!RoleGuard.validadorOperaAsignacion(connection, idValidador, idAsignacion)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "ASIGNACION_NO_AUTORIZADA",
                            "No operas esa asignacion de validacion");
                }

                Integer idEntrada = QrTokenService.idEntradaDe(codigo);
                if (idEntrada == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.BAD_REQUEST, "QR_ILEGIBLE", "El codigo QR no tiene un formato valido");
                }

                String semilla = semillaQr(connection, idEntrada);
                if (semilla == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.NOT_FOUND, "CREDENCIAL_INEXISTENTE",
                            "La entrada no tiene credencial QR");
                }

                // Bloquea la entrada (validar-una-sola-vez atomico).
                String estadoEntrada;
                int entradaEvento, entradaSector;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT e.estado_entrada, r.id_evento, r.id_sector "
                        + "FROM ENTRADA e JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta "
                        + "WHERE e.id_entrada = ? FOR UPDATE")) {
                    ps.setInt(1, idEntrada);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            connection.rollback();
                            return ApiResponses.error(HttpStatus.NOT_FOUND, "ENTRADA_INEXISTENTE", "La entrada no existe");
                        }
                        estadoEntrada = rs.getString("estado_entrada");
                        entradaEvento = rs.getInt("id_evento");
                        entradaSector = rs.getInt("id_sector");
                    }
                }

                // Datos de la asignacion (evento/sector autorizado + horario).
                int asignEvento, asignSector;
                LocalDateTime fin = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT adv.id_evento, adv.id_sector, ev.fecha_hora_fin "
                        + "FROM ASIGNACION_DISPOSITIVO_VALIDADOR adv JOIN EVENTO ev ON ev.id_evento = adv.id_evento "
                        + "WHERE adv.id_asignacion_dispositivo_validador = ?")) {
                    ps.setInt(1, idAsignacion);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        asignEvento = rs.getInt("id_evento");
                        asignSector = rs.getInt("id_sector");
                        if (rs.getTimestamp("fecha_hora_fin") != null) fin = rs.getTimestamp("fecha_hora_fin").toLocalDateTime();
                    }
                }

                // Decision de validacion.
                String motivo = null;
                if (!QrTokenService.verificar(codigo, semilla, TOLERANCIA_VENTANAS)) {
                    motivo = "QR_VENCIDO";
                } else if (!"EMITIDA".equals(estadoEntrada)) {
                    motivo = "ENTRADA_CONSUMIDA";
                } else if (entradaEvento != asignEvento || entradaSector != asignSector) {
                    motivo = "DISPOSITIVO_NO_AUTORIZADO";
                } else if (fin != null && LocalDateTime.now().isAfter(fin)) {
                    motivo = "FUERA_DE_HORARIO";
                }

                String resultado;
                if (motivo == null) {
                    int filas;
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE ENTRADA SET estado_entrada = 'CONSUMIDA' WHERE id_entrada = ? AND estado_entrada = 'EMITIDA'")) {
                        ps.setInt(1, idEntrada);
                        filas = ps.executeUpdate();
                    }
                    if (filas == 1) {
                        resultado = "ACEPTADA";
                    } else {
                        resultado = "RECHAZADA";
                        motivo = "ENTRADA_CONSUMIDA";
                    }
                } else {
                    resultado = "RECHAZADA";
                }

                // Auditoria: siempre se registra la lectura.
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO LECTURA_DE_VALIDACION_INGRESO "
                        + "(id_entrada, id_asignacion_dispositivo_validador, codigo_qr_leido, resultado_validacion, motivo_rechazo) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setInt(1, idEntrada);
                    ps.setInt(2, idAsignacion);
                    ps.setString(3, codigo);
                    ps.setString(4, resultado);
                    if (motivo == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, motivo);
                    ps.executeUpdate();
                }

                connection.commit();

                Map<String, Object> r = ApiResponses.base("OK", "VALIDACION_" + resultado);
                r.put("resultado", resultado);
                r.put("motivo", motivo);
                r.put("idEntrada", idEntrada);
                r.put("message", "ACEPTADA".equals(resultado) ? "Ingreso aceptado; entrada consumida" : "Ingreso rechazado: " + motivo);
                return ResponseEntity.ok(r);

            } catch (SQLException e) {
                ApiResponses.rollbackSilencioso(connection);
                return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_VALIDACION",
                        "No se pudo registrar la validacion");
            }
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }

    private static String semillaQr(Connection connection, int idEntrada) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT semilla_qr FROM CREDENCIAL_QR WHERE id_entrada = ?")) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static String str(java.sql.Timestamp t) { return t == null ? null : t.toLocalDateTime().toString(); }

    public static class ValidarRequest {
        public Integer idAsignacionDispositivoValidador;
        public String codigoQrLeido;
    }
}
