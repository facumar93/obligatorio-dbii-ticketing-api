package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Reportes estadisticos (rol admin). Consultas de agregacion sobre el modelo:
 * eventos mas vendidos, mayores compradores, ocupacion, transferencias y validaciones.
 */
@RestController
public class ReporteController {

    @GetMapping("/reportes/ventas-por-evento")
    public ResponseEntity<?> ventasPorEvento(@RequestHeader(value = "Authorization", required = false) String auth) {
        return reporte(auth, """
                SELECT ev.id_evento, sl.nombre AS local, sv.nombre AS visitante, est.nombre AS estadio,
                       COUNT(en.id_entrada) AS entradas_vendidas
                FROM EVENTO ev
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                LEFT JOIN RESERVA_POR_VENTA r ON r.id_evento = ev.id_evento
                LEFT JOIN ENTRADA en ON en.id_reserva_por_venta = r.id_reserva_por_venta
                     AND en.estado_entrada IN ('EMITIDA', 'CONSUMIDA')
                GROUP BY ev.id_evento, sl.nombre, sv.nombre, est.nombre
                ORDER BY entradas_vendidas DESC, ev.id_evento
                """);
    }

    @GetMapping("/reportes/mayores-compradores")
    public ResponseEntity<?> mayoresCompradores(@RequestHeader(value = "Authorization", required = false) String auth) {
        return reporte(auth, """
                SELECT ug.id_usuario, ug.nombre, ug.apellido, ug.correo,
                       COUNT(v.id_venta) AS ventas, SUM(v.monto_total) AS total_invertido
                FROM USUARIO_GENERAL ug
                JOIN VENTA v ON v.id_usuario_comprador = ug.id_usuario AND v.estado_venta = 'PAGA'
                GROUP BY ug.id_usuario, ug.nombre, ug.apellido, ug.correo
                ORDER BY total_invertido DESC, ventas DESC
                LIMIT 20
                """);
    }

    @GetMapping("/reportes/ocupacion")
    public ResponseEntity<?> ocupacion(@RequestHeader(value = "Authorization", required = false) String auth) {
        return reporte(auth, """
                SELECT ev.id_evento, sl.nombre AS local, sv.nombre AS visitante, s.nombre_sector,
                       es.capacidad_habilitada,
                       COUNT(en.id_entrada) AS vendidas,
                       ROUND(100 * COUNT(en.id_entrada) / es.capacidad_habilitada, 1) AS pct_ocupacion
                FROM EVENTO_SECTOR es
                JOIN EVENTO ev ON ev.id_evento = es.id_evento
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                JOIN SECTOR s ON s.id_sector = es.id_sector
                LEFT JOIN RESERVA_POR_VENTA r ON r.id_evento = es.id_evento AND r.id_sector = es.id_sector
                LEFT JOIN ENTRADA en ON en.id_reserva_por_venta = r.id_reserva_por_venta
                     AND en.estado_entrada IN ('EMITIDA', 'CONSUMIDA')
                GROUP BY ev.id_evento, sl.nombre, sv.nombre, s.nombre_sector, es.capacidad_habilitada
                ORDER BY ev.id_evento, s.nombre_sector
                """);
    }

    @GetMapping("/reportes/transferencias")
    public ResponseEntity<?> transferencias(@RequestHeader(value = "Authorization", required = false) String auth) {
        return reporte(auth, """
                SELECT estado_movimiento, COUNT(*) AS cantidad
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE tipo_movimiento = 'TRANSFERENCIA'
                GROUP BY estado_movimiento
                ORDER BY cantidad DESC
                """);
    }

    @GetMapping("/reportes/validaciones")
    public ResponseEntity<?> validaciones(@RequestHeader(value = "Authorization", required = false) String auth) {
        return reporte(auth, """
                SELECT resultado_validacion, motivo_rechazo, COUNT(*) AS cantidad
                FROM LECTURA_DE_VALIDACION_INGRESO
                GROUP BY resultado_validacion, motivo_rechazo
                ORDER BY cantidad DESC
                """);
    }

    // --- helper generico: corre el SQL (sin params) y mapea filas a camelCase ---

    private ResponseEntity<?> reporte(String auth, String sql) {
        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(auth);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }
        try (Connection conn = DbConnectionFactory.getConnection()) {
            Integer idAdmin = RoleGuard.resolveIdUsuario(conn, token.uid());
            if (idAdmin == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO", "El usuario no esta registrado");
            }
            if (!RoleGuard.esAdmin(conn, idAdmin)) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_ADMIN", "Se requiere rol administrador");
            }
            List<Map<String, Object>> filas = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        fila.put(camel(md.getColumnLabel(i)), rs.getObject(i));
                    }
                    filas.add(fila);
                }
            }
            return ResponseEntity.ok(filas);
        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_REPORTE", "No se pudo generar el reporte");
        }
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toLowerCase().toCharArray()) {
            if (c == '_') { up = true; }
            else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return sb.toString();
    }
}
