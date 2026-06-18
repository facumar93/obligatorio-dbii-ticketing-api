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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class VentasCatalogoController {

    @GetMapping("/ventas/catalogo")
    public ResponseEntity<?> listarEventosVendibles(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        try {
            FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        String sql = """
                SELECT
                    e.id_evento,
                    e.fecha_hora_inicio,
                    e.fecha_hora_fin,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    est.nombre AS estadio,
                    est.ciudad
                FROM EVENTO e
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = e.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = e.id_seleccion_visitante
                JOIN ESTADIO est
                    ON est.id_estadio = e.id_estadio
                WHERE e.fecha_hora_inicio > NOW()
                  AND EXISTS (
                      SELECT 1
                      FROM EVENTO_SECTOR es
                      WHERE es.id_evento = e.id_evento
                  )
                ORDER BY e.fecha_hora_inicio, e.id_evento
                """;

        List<Map<String, Object>> eventos = new ArrayList<>();

        try (
            Connection connection = DbConnectionFactory.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)
        ) {
            while (resultSet.next()) {
                eventos.add(eventoDesdeResultSet(resultSet));
            }

            return ResponseEntity.ok(eventos);

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_VENTAS_CATALOGO");
            respuesta.put("message", "No se pudo cargar el catalogo de eventos");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @GetMapping("/ventas/catalogo/{id_evento}")
    public ResponseEntity<?> obtenerDetalleEvento(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("id_evento") int idEvento) {

        try {
            FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Map<String, Object> evento = buscarEventoVendible(connection, idEvento);

            if (evento == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "EVENTO_NO_DISPONIBLE");
                respuesta.put("message", "El evento no existe, ya no esta disponible o no tiene sectores habilitados");

                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
            }

            evento.put("sectores", buscarSectoresDelEvento(connection, idEvento));

            return ResponseEntity.ok(evento);

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_VENTAS_CATALOGO_DETALLE");
            respuesta.put("message", "No se pudo cargar el detalle del evento");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static Map<String, Object> buscarEventoVendible(Connection connection, int idEvento) throws SQLException {
        String sql = """
                SELECT
                    e.id_evento,
                    e.fecha_hora_inicio,
                    e.fecha_hora_fin,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    est.nombre AS estadio,
                    est.ciudad
                FROM EVENTO e
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = e.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = e.id_seleccion_visitante
                JOIN ESTADIO est
                    ON est.id_estadio = e.id_estadio
                WHERE e.id_evento = ?
                  AND e.fecha_hora_inicio > NOW()
                  AND EXISTS (
                      SELECT 1
                      FROM EVENTO_SECTOR es
                      WHERE es.id_evento = e.id_evento
                  )
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return eventoDesdeResultSet(resultSet);
            }
        }
    }

    private static List<Map<String, Object>> buscarSectoresDelEvento(Connection connection, int idEvento)
            throws SQLException {

        String sql = """
                SELECT
                    es.id_sector,
                    s.nombre_sector,
                    es.precio_entrada,
                    es.capacidad_habilitada,
                    COALESCE(ocupado.cantidad_ocupada, 0) AS cantidad_ocupada,
                    es.capacidad_habilitada - COALESCE(ocupado.cantidad_ocupada, 0) AS cupo_disponible
                FROM EVENTO_SECTOR es
                JOIN SECTOR s
                    ON s.id_sector = es.id_sector
                LEFT JOIN (
                    SELECT
                        rpv.id_evento,
                        rpv.id_sector,
                        SUM(rpv.cantidad) AS cantidad_ocupada
                    FROM RESERVA_POR_VENTA rpv
                    JOIN VENTA v
                        ON v.id_venta = rpv.id_venta
                    WHERE rpv.id_evento = ?
                      AND (
                          rpv.estado_reserva = 'EMITIDA'
                          OR (
                              rpv.estado_reserva = 'RESERVADA'
                              AND v.estado_venta = 'PENDIENTE'
                              AND v.fecha_expiracion > NOW()
                          )
                      )
                    GROUP BY rpv.id_evento, rpv.id_sector
                ) ocupado
                    ON ocupado.id_evento = es.id_evento
                   AND ocupado.id_sector = es.id_sector
                WHERE es.id_evento = ?
                ORDER BY s.nombre_sector, es.id_sector
                """;

        List<Map<String, Object>> sectores = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEvento);
            statement.setInt(2, idEvento);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> sector = new LinkedHashMap<>();
                    sector.put("idSector", resultSet.getInt("id_sector"));
                    sector.put("nombreSector", resultSet.getString("nombre_sector").trim());
                    sector.put("precioEntrada", resultSet.getBigDecimal("precio_entrada"));
                    sector.put("capacidadHabilitada", resultSet.getInt("capacidad_habilitada"));
                    sector.put("cantidadOcupada", resultSet.getInt("cantidad_ocupada"));
                    sector.put("cupoDisponible", resultSet.getInt("cupo_disponible"));

                    sectores.add(sector);
                }
            }
        }

        return sectores;
    }

    private static Map<String, Object> eventoDesdeResultSet(ResultSet resultSet) throws SQLException {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("idEvento", resultSet.getInt("id_evento"));
        evento.put("fechaHoraInicio", timestampComoTexto(resultSet, "fecha_hora_inicio"));
        evento.put("fechaHoraFin", timestampComoTexto(resultSet, "fecha_hora_fin"));
        evento.put("seleccionLocal", resultSet.getString("seleccion_local").trim());
        evento.put("seleccionVisitante", resultSet.getString("seleccion_visitante").trim());
        evento.put("estadio", resultSet.getString("estadio").trim());
        evento.put("ciudad", resultSet.getString("ciudad").trim());
        return evento;
    }

    private static String timestampComoTexto(ResultSet resultSet, String columna) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columna);
        return timestamp.toLocalDateTime().toString();
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
}
