package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.ResultSet;
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
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class CarteleraController {

    @GetMapping("/cartelera")
    public ResponseEntity<?> listarCartelera() {
        String sql = """
                SELECT
                    e.id_evento,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    e.fecha_hora_inicio
                FROM EVENTO e
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = e.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = e.id_seleccion_visitante
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
                Map<String, Object> evento = new LinkedHashMap<>();
                evento.put("idEvento", resultSet.getInt("id_evento"));
                evento.put("seleccionLocal", resultSet.getString("seleccion_local").trim());
                evento.put("seleccionVisitante", resultSet.getString("seleccion_visitante").trim());
                evento.put("fechaHoraInicio", timestampAString(resultSet.getTimestamp("fecha_hora_inicio")));

                eventos.add(evento);
            }

            return ResponseEntity.ok(eventos);

        } catch (Exception e) {
            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("status", "ERROR");
            respuesta.put("code", "ERROR_DB_CARTELERA");
            respuesta.put("message", "No se pudo cargar la cartelera");
            respuesta.put("timestamp", OffsetDateTime.now().toString());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static String timestampAString(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toLocalDateTime().toString();
    }
}
