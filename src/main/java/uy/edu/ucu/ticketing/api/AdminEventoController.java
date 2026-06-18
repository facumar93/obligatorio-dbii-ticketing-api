package uy.edu.ucu.ticketing.api;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Operaciones de administracion de eventos. El admin solo opera estadios de su
 * pais sede con asignacion vigente. El alta de evento inserta EVENTO + sus
 * EVENTO_SECTOR atomicamente y registra la asignacion que lo dio de alta.
 */
@RestController
public class AdminEventoController {

    // --- Catalogos para el formulario de alta ---

    @GetMapping("/admin/estadios")
    public ResponseEntity<?> estadios(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conAdmin(auth, (conn, idAdmin) -> {
            String sql = """
                    SELECT e.id_estadio, e.nombre, e.ciudad, p.codigo_iso, p.nombre AS pais
                    FROM ESTADIO e
                    JOIN ASIGNACION_ADMIN_PAIS_SEDE a
                         ON a.id_pais_sede = e.id_pais_sede AND a.id_usuario_admin = ? AND a.fecha_hasta IS NULL
                    JOIN PAIS p ON p.id_pais = e.id_pais_sede
                    ORDER BY e.nombre
                    """;
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, idAdmin);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("idEstadio", rs.getInt("id_estadio"));
                        m.put("nombre", rs.getString("nombre"));
                        m.put("ciudad", rs.getString("ciudad"));
                        m.put("pais", rs.getString("pais"));
                        m.put("codigoIso", rs.getString("codigo_iso").trim());
                        out.add(m);
                    }
                }
            }
            return ResponseEntity.ok(out);
        });
    }

    @GetMapping("/admin/estadios/{id}/sectores")
    public ResponseEntity<?> sectores(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable("id") int idEstadio) {
        return conAdmin(auth, (conn, idAdmin) -> {
            String sql = "SELECT id_sector, nombre_sector, capacidad_max FROM SECTOR WHERE id_estadio = ? ORDER BY nombre_sector";
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, idEstadio);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("idSector", rs.getInt("id_sector"));
                        m.put("nombreSector", rs.getString("nombre_sector"));
                        m.put("capacidadMax", rs.getInt("capacidad_max"));
                        out.add(m);
                    }
                }
            }
            return ResponseEntity.ok(out);
        });
    }

    @GetMapping("/admin/selecciones")
    public ResponseEntity<?> selecciones(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conAdmin(auth, (conn, idAdmin) -> {
            String sql = "SELECT s.id_seleccion, s.nombre, p.codigo_iso FROM SELECCION_NACIONAL s JOIN PAIS p ON p.id_pais = s.id_pais ORDER BY s.nombre";
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idSeleccion", rs.getInt("id_seleccion"));
                    m.put("nombre", rs.getString("nombre"));
                    m.put("codigoIso", rs.getString("codigo_iso").trim());
                    out.add(m);
                }
            }
            return ResponseEntity.ok(out);
        });
    }

    // --- Alta de evento ---

    @PostMapping("/admin/eventos")
    public ResponseEntity<Map<String, Object>> altaEvento(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) AltaEventoRequest req) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        if (req == null || req.idEstadio == null || req.idSeleccionLocal == null || req.idSeleccionVisitante == null
                || req.fechaHoraInicio == null || req.fechaHoraFin == null
                || req.sectores == null || req.sectores.isEmpty()) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS",
                    "Faltan datos del evento o sectores");
        }

        LocalDateTime inicio, fin;
        try {
            inicio = LocalDateTime.parse(req.fechaHoraInicio);
            fin = LocalDateTime.parse(req.fechaHoraFin);
        } catch (Exception e) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "FECHA_INVALIDA",
                    "Formato de fecha/hora invalido (se espera ISO, ej 2026-07-10T16:00)");
        }
        if (!fin.isAfter(inicio)) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "RANGO_FECHAS_INVALIDO",
                    "La fecha/hora de fin debe ser posterior al inicio");
        }
        if (req.idSeleccionLocal.equals(req.idSeleccionVisitante)) {
            return ApiResponses.error(HttpStatus.BAD_REQUEST, "SELECCIONES_IGUALES",
                    "Local y visitante deben ser selecciones distintas");
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer idAdmin = RoleGuard.resolveIdUsuario(connection, token.uid());
                if (idAdmin == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                            "El usuario autenticado no esta registrado en el sistema");
                }
                Integer idAsignacion = RoleGuard.asignacionVigentePara(connection, idAdmin, req.idEstadio);
                if (idAsignacion == null) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.FORBIDDEN, "ADMIN_SIN_ASIGNACION_VIGENTE",
                            "No administras el pais sede de ese estadio");
                }

                if (eventosSolapados(connection, req.idEstadio, inicio, fin)) {
                    connection.rollback();
                    return ApiResponses.error(HttpStatus.CONFLICT, "EVENTO_SOLAPADO",
                            "Ya hay un evento en ese estadio en ese horario");
                }

                // Validacion de cada sector contra el estadio.
                for (SectorHabilitado s : req.sectores) {
                    if (s.idSector == null || s.precioEntrada == null || s.capacidadHabilitada == null) {
                        connection.rollback();
                        return ApiResponses.error(HttpStatus.BAD_REQUEST, "SECTOR_INVALIDO",
                                "Cada sector requiere idSector, precioEntrada y capacidadHabilitada");
                    }
                    Integer capMax = capacidadMaxSiPertenece(connection, s.idSector, req.idEstadio);
                    if (capMax == null) {
                        connection.rollback();
                        return ApiResponses.error(HttpStatus.BAD_REQUEST, "SECTOR_NO_PERTENECE_ESTADIO",
                                "El sector " + s.idSector + " no pertenece al estadio");
                    }
                    if (s.precioEntrada.compareTo(BigDecimal.ZERO) <= 0) {
                        connection.rollback();
                        return ApiResponses.error(HttpStatus.BAD_REQUEST, "PRECIO_INVALIDO",
                                "El precio debe ser mayor a 0");
                    }
                    if (s.capacidadHabilitada < 1 || s.capacidadHabilitada > capMax) {
                        connection.rollback();
                        return ApiResponses.error(HttpStatus.BAD_REQUEST, "CAPACIDAD_INVALIDA",
                                "La capacidad habilitada debe estar entre 1 y la capacidad maxima del sector (" + capMax + ")");
                    }
                }

                int idEvento;
                String sqlEvento = """
                        INSERT INTO EVENTO (fecha_hora_inicio, fecha_hora_fin, id_estadio,
                                            id_seleccion_local, id_seleccion_visitante, id_asignacion_alta)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(sqlEvento, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setTimestamp(1, Timestamp.valueOf(inicio));
                    ps.setTimestamp(2, Timestamp.valueOf(fin));
                    ps.setInt(3, req.idEstadio);
                    ps.setInt(4, req.idSeleccionLocal);
                    ps.setInt(5, req.idSeleccionVisitante);
                    ps.setInt(6, idAsignacion);
                    ps.executeUpdate();
                    try (ResultSet gk = ps.getGeneratedKeys()) {
                        if (!gk.next()) throw new SQLException("No se obtuvo id_evento");
                        idEvento = gk.getInt(1);
                    }
                }

                String sqlES = "INSERT INTO EVENTO_SECTOR (id_evento, id_sector, precio_entrada, capacidad_habilitada) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sqlES)) {
                    for (SectorHabilitado s : req.sectores) {
                        ps.setInt(1, idEvento);
                        ps.setInt(2, s.idSector);
                        ps.setBigDecimal(3, s.precioEntrada);
                        ps.setInt(4, s.capacidadHabilitada);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                connection.commit();
                Map<String, Object> r = ApiResponses.base("OK", "EVENTO_CREADO");
                r.put("message", "Evento dado de alta con sus sectores");
                r.put("idEvento", idEvento);
                r.put("sectores", req.sectores.size());
                return ResponseEntity.status(HttpStatus.CREATED).body(r);

            } catch (SQLIntegrityConstraintViolationException e) {
                ApiResponses.rollbackSilencioso(connection);
                if (e.getErrorCode() == 1062) {
                    return ApiResponses.error(HttpStatus.CONFLICT, "SECTOR_DUPLICADO",
                            "Un sector fue habilitado dos veces para el evento");
                }
                return ApiResponses.error(HttpStatus.BAD_REQUEST, "CATALOGO_INVALIDO",
                        "Algun estadio/seleccion/sector referenciado no existe");
            } catch (SQLException e) {
                ApiResponses.rollbackSilencioso(connection);
                return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ALTA_EVENTO",
                        "No se pudo dar de alta el evento");
            }
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }

    // --- helpers ---

    private static boolean eventosSolapados(Connection conn, int idEstadio, LocalDateTime inicio, LocalDateTime fin) throws SQLException {
        String sql = "SELECT 1 FROM EVENTO WHERE id_estadio = ? AND fecha_hora_inicio < ? AND fecha_hora_fin > ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idEstadio);
            ps.setTimestamp(2, Timestamp.valueOf(fin));
            ps.setTimestamp(3, Timestamp.valueOf(inicio));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static Integer capacidadMaxSiPertenece(Connection conn, int idSector, int idEstadio) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT capacidad_max FROM SECTOR WHERE id_sector = ? AND id_estadio = ?")) {
            ps.setInt(1, idSector);
            ps.setInt(2, idEstadio);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    // Plantilla para endpoints admin de solo lectura: verifica token, rol admin y delega.
    interface AdminHandler { ResponseEntity<?> run(Connection conn, int idAdmin) throws SQLException; }

    private static ResponseEntity<?> conAdmin(String auth, AdminHandler handler) {
        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(auth);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }
        try (Connection conn = DbConnectionFactory.getConnection()) {
            Integer idAdmin = RoleGuard.resolveIdUsuario(conn, token.uid());
            if (idAdmin == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }
            if (!RoleGuard.esAdmin(conn, idAdmin)) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_ADMIN", "Se requiere rol administrador");
            }
            return handler.run(conn, idAdmin);
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ADMIN",
                    "Error de base de datos en operacion administrativa");
        }
    }

    public static class AltaEventoRequest {
        public Integer idEstadio;
        public Integer idSeleccionLocal;
        public Integer idSeleccionVisitante;
        public String fechaHoraInicio;
        public String fechaHoraFin;
        public List<SectorHabilitado> sectores;
    }

    public static class SectorHabilitado {
        public Integer idSector;
        public BigDecimal precioEntrada;
        public Integer capacidadHabilitada;
    }
}
