package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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
 * Dispositivos de escaneo autorizados, su vinculacion a un funcionario de
 * validacion y su asignacion a un evento/sector concreto. Todo bajo rol admin.
 */
@RestController
public class AdminDispositivoController {

    @GetMapping("/admin/validadores")
    public ResponseEntity<?> validadores(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conAdmin(auth, (conn, idAdmin) -> {
            String sql = """
                    SELECT uv.id_usuario, uv.numero_legajo, ug.nombre, ug.apellido, ug.correo
                    FROM USUARIO_DE_VALIDACION uv
                    JOIN USUARIO_GENERAL ug ON ug.id_usuario = uv.id_usuario
                    ORDER BY ug.apellido, ug.nombre
                    """;
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idUsuario", rs.getInt("id_usuario"));
                    m.put("numeroLegajo", rs.getString("numero_legajo"));
                    m.put("nombre", rs.getString("nombre"));
                    m.put("apellido", rs.getString("apellido"));
                    m.put("correo", rs.getString("correo"));
                    out.add(m);
                }
            }
            return ResponseEntity.ok(out);
        });
    }

    @GetMapping("/admin/dispositivos")
    public ResponseEntity<?> dispositivos(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conAdmin(auth, (conn, idAdmin) -> {
            String sql = """
                    SELECT d.id_dispositivo, d.estado, d.fecha_alta,
                           v.id_vinculacion, ug.nombre, ug.apellido
                    FROM DISPOSITIVO_AUTORIZADO d
                    LEFT JOIN VINCULACION_VALIDADOR_DISPOSITIVO v
                         ON v.id_dispositivo = d.id_dispositivo AND v.estado_vinculacion = 'ACTIVA' AND v.fecha_hasta IS NULL
                    LEFT JOIN USUARIO_GENERAL ug ON ug.id_usuario = v.id_usuario_validador
                    ORDER BY d.id_dispositivo
                    """;
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idDispositivo", rs.getInt("id_dispositivo"));
                    m.put("estado", rs.getString("estado"));
                    m.put("fechaAlta", str(rs.getTimestamp("fecha_alta")));
                    int idVinc = rs.getInt("id_vinculacion");
                    m.put("idVinculacionActiva", rs.wasNull() ? null : idVinc);
                    String n = rs.getString("nombre");
                    m.put("validador", n == null ? null : (n + " " + rs.getString("apellido")).trim());
                    out.add(m);
                }
            }
            return ResponseEntity.ok(out);
        });
    }

    @PostMapping("/admin/dispositivos")
    public ResponseEntity<Map<String, Object>> crearDispositivo(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conAdminWrite(auth, (conn, idAdmin) -> {
            int idDispositivo;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO DISPOSITIVO_AUTORIZADO (estado, fecha_alta) VALUES ('ACTIVO', CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet gk = ps.getGeneratedKeys()) { gk.next(); idDispositivo = gk.getInt(1); }
            }
            conn.commit();
            Map<String, Object> r = ApiResponses.base("OK", "DISPOSITIVO_REGISTRADO");
            r.put("message", "Dispositivo autorizado registrado");
            r.put("idDispositivo", idDispositivo);
            return ResponseEntity.status(HttpStatus.CREATED).body(r);
        });
    }

    @PostMapping("/admin/vinculaciones")
    public ResponseEntity<Map<String, Object>> vincular(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody(required = false) VinculacionRequest req) {
        return conAdminWrite(auth, (conn, idAdmin) -> {
            if (req == null || req.idUsuarioValidador == null || req.idDispositivo == null) {
                conn.rollback();
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", "Se requiere idUsuarioValidador e idDispositivo");
            }
            // Bloquea el dispositivo para evitar doble vinculacion activa.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT estado FROM DISPOSITIVO_AUTORIZADO WHERE id_dispositivo = ? FOR UPDATE")) {
                ps.setInt(1, req.idDispositivo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return ApiResponses.error(HttpStatus.NOT_FOUND, "DISPOSITIVO_INEXISTENTE", "El dispositivo no existe"); }
                    if (!"ACTIVO".equals(rs.getString(1))) { conn.rollback(); return ApiResponses.error(HttpStatus.CONFLICT, "DISPOSITIVO_NO_ACTIVO", "El dispositivo no esta activo"); }
                }
            }
            if (!existe(conn, "SELECT 1 FROM USUARIO_DE_VALIDACION WHERE id_usuario = ?", req.idUsuarioValidador)) {
                conn.rollback();
                return ApiResponses.error(HttpStatus.NOT_FOUND, "VALIDADOR_INEXISTENTE", "El usuario no es funcionario de validacion");
            }
            if (existe(conn, "SELECT 1 FROM VINCULACION_VALIDADOR_DISPOSITIVO WHERE id_dispositivo = ? AND estado_vinculacion = 'ACTIVA' AND fecha_hasta IS NULL", req.idDispositivo)) {
                conn.rollback();
                return ApiResponses.error(HttpStatus.CONFLICT, "DISPOSITIVO_YA_VINCULADO", "El dispositivo ya tiene una vinculacion activa");
            }
            int idVinc;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO VINCULACION_VALIDADOR_DISPOSITIVO (id_usuario_validador, id_dispositivo, fecha_desde, estado_vinculacion) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP, 'ACTIVA')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, req.idUsuarioValidador);
                ps.setInt(2, req.idDispositivo);
                ps.executeUpdate();
                try (ResultSet gk = ps.getGeneratedKeys()) { gk.next(); idVinc = gk.getInt(1); }
            }
            conn.commit();
            Map<String, Object> r = ApiResponses.base("OK", "VINCULACION_CREADA");
            r.put("message", "Validador vinculado al dispositivo");
            r.put("idVinculacion", idVinc);
            return ResponseEntity.status(HttpStatus.CREATED).body(r);
        });
    }

    @PostMapping("/admin/asignaciones-dispositivo")
    public ResponseEntity<Map<String, Object>> asignar(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody(required = false) AsignacionRequest req) {
        return conAdminWrite(auth, (conn, idAdmin) -> {
            if (req == null || req.idVinculacion == null || req.idEvento == null || req.idSector == null) {
                conn.rollback();
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", "Se requiere idVinculacion, idEvento e idSector");
            }
            if (!existe(conn, "SELECT 1 FROM VINCULACION_VALIDADOR_DISPOSITIVO WHERE id_vinculacion = ? AND estado_vinculacion = 'ACTIVA'", req.idVinculacion)) {
                conn.rollback();
                return ApiResponses.error(HttpStatus.NOT_FOUND, "VINCULACION_INEXISTENTE", "No existe una vinculacion activa con ese id");
            }
            // El evento/sector debe existir y estar en la jurisdiccion vigente del admin.
            LocalDateTime inicio = null, fin = null;
            String sql = """
                    SELECT ev.fecha_hora_inicio, ev.fecha_hora_fin
                    FROM EVENTO_SECTOR es
                    JOIN EVENTO ev ON ev.id_evento = es.id_evento
                    JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                    JOIN ASIGNACION_ADMIN_PAIS_SEDE a ON a.id_pais_sede = est.id_pais_sede
                         AND a.id_usuario_admin = ? AND a.fecha_hasta IS NULL
                    WHERE es.id_evento = ? AND es.id_sector = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, idAdmin);
                ps.setInt(2, req.idEvento);
                ps.setInt(3, req.idSector);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return ApiResponses.error(HttpStatus.FORBIDDEN, "EVENTO_SECTOR_NO_AUTORIZADO",
                                "El evento/sector no existe o no esta en tu jurisdiccion");
                    }
                    if (rs.getTimestamp("fecha_hora_inicio") != null) inicio = rs.getTimestamp("fecha_hora_inicio").toLocalDateTime();
                    if (rs.getTimestamp("fecha_hora_fin") != null) fin = rs.getTimestamp("fecha_hora_fin").toLocalDateTime();
                }
            }
            LocalDateTime ahora = LocalDateTime.now();
            LocalDateTime activacion = (inicio != null && inicio.isAfter(ahora)) ? inicio : ahora;
            LocalDateTime desactivacion = (fin != null && fin.isAfter(activacion)) ? fin : activacion.plusHours(3);

            int idAsig;
            // Las tres fechas salen del MISMO reloj (JVM) para no violar los CHECK de orden
            // (fecha_asignacion <= fecha_activacion <= fecha_desactivacion) por skew/timezone vs MySQL.
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ASIGNACION_DISPOSITIVO_VALIDADOR (id_vinculacion, id_evento, id_sector, "
                    + "fecha_asignacion, fecha_activacion, fecha_desactivacion) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, req.idVinculacion);
                ps.setInt(2, req.idEvento);
                ps.setInt(3, req.idSector);
                ps.setTimestamp(4, Timestamp.valueOf(ahora));
                ps.setTimestamp(5, Timestamp.valueOf(activacion));
                ps.setTimestamp(6, Timestamp.valueOf(desactivacion));
                ps.executeUpdate();
                try (ResultSet gk = ps.getGeneratedKeys()) { gk.next(); idAsig = gk.getInt(1); }
            }
            conn.commit();
            Map<String, Object> r = ApiResponses.base("OK", "ASIGNACION_DISPOSITIVO_CREADA");
            r.put("message", "Dispositivo asignado al evento/sector");
            r.put("idAsignacionDispositivoValidador", idAsig);
            return ResponseEntity.status(HttpStatus.CREATED).body(r);
        });
    }

    // --- helpers ---

    interface AdminRead { ResponseEntity<?> run(Connection conn, int idAdmin) throws SQLException; }
    interface AdminWrite { ResponseEntity<Map<String, Object>> run(Connection conn, int idAdmin) throws SQLException; }

    private static ResponseEntity<?> conAdmin(String auth, AdminRead handler) {
        VerifiedFirebaseToken token;
        try { token = FirebaseTokenVerifier.verifyAuthorizationHeader(auth); }
        catch (InvalidAuthorizationException e) { return ApiResponses.noAutorizado(e.getCode(), e.getMessage()); }
        try (Connection conn = DbConnectionFactory.getConnection()) {
            Integer idAdmin = RoleGuard.resolveIdUsuario(conn, token.uid());
            if (idAdmin == null) return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO", "El usuario no esta registrado");
            if (!RoleGuard.esAdmin(conn, idAdmin)) return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_ADMIN", "Se requiere rol administrador");
            return handler.run(conn, idAdmin);
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ADMIN", "Error de base de datos");
        }
    }

    private static ResponseEntity<Map<String, Object>> conAdminWrite(String auth, AdminWrite handler) {
        VerifiedFirebaseToken token;
        try { token = FirebaseTokenVerifier.verifyAuthorizationHeader(auth); }
        catch (InvalidAuthorizationException e) { return ApiResponses.noAutorizado(e.getCode(), e.getMessage()); }
        try (Connection conn = DbConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Integer idAdmin = RoleGuard.resolveIdUsuario(conn, token.uid());
                if (idAdmin == null) { conn.rollback(); return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO", "El usuario no esta registrado"); }
                if (!RoleGuard.esAdmin(conn, idAdmin)) { conn.rollback(); return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_ADMIN", "Se requiere rol administrador"); }
                return handler.run(conn, idAdmin);
            } catch (SQLException e) {
                ApiResponses.rollbackSilencioso(conn);
                return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ADMIN", "Error de base de datos en operacion administrativa");
            }
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION", "No se pudo abrir la conexion");
        }
    }

    private static boolean existe(Connection conn, String sql, int param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static String str(java.sql.Timestamp t) { return t == null ? null : t.toLocalDateTime().toString(); }

    public static class VinculacionRequest {
        public Integer idUsuarioValidador;
        public Integer idDispositivo;
    }

    public static class AsignacionRequest {
        public Integer idVinculacion;
        public Integer idEvento;
        public Integer idSector;
    }
}
