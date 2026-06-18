package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Historial de compras (ventas) del usuario autenticado, con sus lineas de reserva.
 * Solo lectura. Separado de VentaController (que es el lado de escritura).
 */
@RestController
public class CompraConsultaController {

    @GetMapping("/mis-compras")
    public ResponseEntity<?> misCompras(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String sqlVentas = """
                SELECT v.id_venta, v.fecha_venta, v.estado_venta, v.monto_base, v.monto_total, t.porcentaje
                FROM VENTA v
                JOIN TASA t ON t.id_tasa_comision = v.id_tasa_comision
                WHERE v.id_usuario_comprador = ?
                ORDER BY v.fecha_venta DESC, v.id_venta DESC
                """;

        String sqlReservas = """
                SELECT r.id_venta, r.id_evento, r.id_sector, r.cantidad, r.subtotal, r.estado_reserva,
                       s.nombre_sector, sl.nombre AS local, sv.nombre AS visitante,
                       ev.fecha_hora_inicio
                FROM RESERVA_POR_VENTA r
                JOIN VENTA v ON v.id_venta = r.id_venta
                JOIN SECTOR s ON s.id_sector = r.id_sector
                JOIN EVENTO ev ON ev.id_evento = r.id_evento
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                WHERE v.id_usuario_comprador = ?
                ORDER BY r.id_venta DESC, r.id_reserva_por_venta
                """;

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }

            // Reservas agrupadas por venta.
            Map<Integer, List<Map<String, Object>>> reservasPorVenta = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(sqlReservas)) {
                ps.setInt(1, idUsuario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int idVenta = rs.getInt("id_venta");
                        Map<String, Object> linea = new LinkedHashMap<>();
                        linea.put("idEvento", rs.getInt("id_evento"));
                        linea.put("idSector", rs.getInt("id_sector"));
                        linea.put("nombreSector", rs.getString("nombre_sector"));
                        linea.put("local", rs.getString("local"));
                        linea.put("visitante", rs.getString("visitante"));
                        linea.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                        linea.put("cantidad", rs.getInt("cantidad"));
                        linea.put("subtotal", rs.getBigDecimal("subtotal"));
                        linea.put("estadoReserva", rs.getString("estado_reserva"));
                        reservasPorVenta.computeIfAbsent(idVenta, k -> new ArrayList<>()).add(linea);
                    }
                }
            }

            List<Map<String, Object>> ventas = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sqlVentas)) {
                ps.setInt(1, idUsuario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int idVenta = rs.getInt("id_venta");
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("idVenta", idVenta);
                        v.put("fechaVenta", str(rs.getTimestamp("fecha_venta")));
                        v.put("estadoVenta", rs.getString("estado_venta"));
                        v.put("montoBase", rs.getBigDecimal("monto_base"));
                        v.put("montoTotal", rs.getBigDecimal("monto_total"));
                        v.put("porcentajeComision", rs.getBigDecimal("porcentaje"));
                        v.put("lineas", reservasPorVenta.getOrDefault(idVenta, List.of()));
                        ventas.add(v);
                    }
                }
            }

            return ResponseEntity.ok(ventas);

        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_MIS_COMPRAS",
                    "No se pudieron cargar las compras");
        }
    }

    private static String str(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }
}
