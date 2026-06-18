package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Catalogo publico de eventos (partidos) y detalle con disponibilidad por sector.
 * Publico (sin token): lo consume el landing y el navegador de eventos.
 * El nombre del partido se deriva de las dos SELECCION_NACIONAL (EVENTO no tiene
 * columna nombre). La disponibilidad es estimacion de display; el cupo autoritativo
 * se controla con FOR UPDATE en VentaController.
 */
@RestController
public class EventoController {

    @GetMapping("/eventos")
    public ResponseEntity<?> listarEventos() {
        String sql = """
                SELECT ev.id_evento, ev.fecha_hora_inicio, ev.fecha_hora_fin,
                       est.nombre AS nombre_estadio, est.ciudad,
                       ps.codigo_iso AS pais_sede_iso, ps.nombre AS pais_sede_nombre,
                       sl.nombre AS local, sv.nombre AS visitante,
                       agg.precio_min, agg.precio_max, agg.cap_total
                FROM EVENTO ev
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                JOIN PAIS ps ON ps.id_pais = est.id_pais_sede
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                LEFT JOIN (
                    SELECT id_evento,
                           MIN(precio_entrada) AS precio_min,
                           MAX(precio_entrada) AS precio_max,
                           SUM(capacidad_habilitada) AS cap_total
                    FROM EVENTO_SECTOR GROUP BY id_evento
                ) agg ON agg.id_evento = ev.id_evento
                ORDER BY ev.fecha_hora_inicio
                """;

        List<Map<String, Object>> eventos = new ArrayList<>();

        try (Connection connection = DbConnectionFactory.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("idEvento", rs.getInt("id_evento"));
                e.put("local", rs.getString("local"));
                e.put("visitante", rs.getString("visitante"));
                e.put("estadio", rs.getString("nombre_estadio"));
                e.put("ciudad", rs.getString("ciudad"));
                e.put("paisSede", rs.getString("pais_sede_nombre"));
                e.put("paisSedeIso", rs.getString("pais_sede_iso").trim());
                e.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                e.put("fechaHoraFin", str(rs.getTimestamp("fecha_hora_fin")));
                e.put("precioMin", rs.getBigDecimal("precio_min"));
                e.put("precioMax", rs.getBigDecimal("precio_max"));
                e.put("capacidadTotal", (Object) (rs.getObject("cap_total") == null ? 0 : rs.getInt("cap_total")));
                eventos.add(e);
            }

            return ResponseEntity.ok(eventos);

        } catch (SQLException | IllegalStateException ex) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_EVENTOS",
                    "No se pudieron cargar los eventos");
        }
    }

    @GetMapping("/eventos/{id}")
    public ResponseEntity<?> detalleEvento(@PathVariable("id") int idEvento) {
        String sqlHeader = """
                SELECT ev.id_evento, ev.fecha_hora_inicio, ev.fecha_hora_fin,
                       est.nombre AS nombre_estadio, est.ciudad,
                       ps.codigo_iso AS pais_sede_iso, ps.nombre AS pais_sede_nombre,
                       sl.nombre AS local, sv.nombre AS visitante
                FROM EVENTO ev
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                JOIN PAIS ps ON ps.id_pais = est.id_pais_sede
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                WHERE ev.id_evento = ?
                """;

        String sqlSectores = """
                SELECT es.id_sector, s.nombre_sector, es.precio_entrada, es.capacidad_habilitada,
                       es.capacidad_habilitada - COALESCE(v.vendidas, 0) AS disponible
                FROM EVENTO_SECTOR es
                JOIN SECTOR s ON s.id_sector = es.id_sector
                LEFT JOIN (
                    SELECT r.id_sector, COUNT(*) AS vendidas
                    FROM ENTRADA e
                    JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta
                    WHERE r.id_evento = ? AND e.estado_entrada IN ('EMITIDA', 'CONSUMIDA')
                    GROUP BY r.id_sector
                ) v ON v.id_sector = es.id_sector
                WHERE es.id_evento = ?
                ORDER BY s.nombre_sector
                """;

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Map<String, Object> evento;

            try (PreparedStatement ps = connection.prepareStatement(sqlHeader)) {
                ps.setInt(1, idEvento);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ApiResponses.error(HttpStatus.NOT_FOUND, "EVENTO_INEXISTENTE",
                                "El evento solicitado no existe");
                    }
                    evento = new LinkedHashMap<>();
                    evento.put("idEvento", rs.getInt("id_evento"));
                    evento.put("local", rs.getString("local"));
                    evento.put("visitante", rs.getString("visitante"));
                    evento.put("estadio", rs.getString("nombre_estadio"));
                    evento.put("ciudad", rs.getString("ciudad"));
                    evento.put("paisSede", rs.getString("pais_sede_nombre"));
                    evento.put("paisSedeIso", rs.getString("pais_sede_iso").trim());
                    evento.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                    evento.put("fechaHoraFin", str(rs.getTimestamp("fecha_hora_fin")));
                }
            }

            List<Map<String, Object>> sectores = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sqlSectores)) {
                ps.setInt(1, idEvento);
                ps.setInt(2, idEvento);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("idSector", rs.getInt("id_sector"));
                        s.put("nombreSector", rs.getString("nombre_sector"));
                        s.put("precio", rs.getBigDecimal("precio_entrada"));
                        s.put("capacidadHabilitada", rs.getInt("capacidad_habilitada"));
                        int disp = rs.getInt("disponible");
                        s.put("disponible", Math.max(disp, 0));
                        sectores.add(s);
                    }
                }
            }

            evento.put("sectores", sectores);
            return ResponseEntity.ok(evento);

        } catch (SQLException | IllegalStateException ex) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_EVENTO",
                    "No se pudo cargar el evento");
        }
    }

    private static String str(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }
}
