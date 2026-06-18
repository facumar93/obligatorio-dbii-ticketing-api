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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

/**
 * Entradas del usuario autenticado (titularidad vigente) + cadena de custodia.
 * La titularidad se deriva del movimiento efectivo vigente (ver TitularidadDao).
 */
@RestController
public class EntradaController {

    @GetMapping("/mis-entradas")
    public ResponseEntity<?> misEntradas(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String sql = """
                SELECT e.id_entrada, e.estado_entrada, e.fecha_emision,
                       r.id_evento, r.id_sector, s.nombre_sector, es.precio_entrada,
                       sl.nombre AS local, sv.nombre AS visitante,
                       est.nombre AS nombre_estadio, est.ciudad, ev.fecha_hora_inicio,
                       (SELECT COUNT(*) FROM MOVIMIENTO_ASIGNACION_ENTRADA mt
                          WHERE mt.id_entrada = e.id_entrada
                            AND mt.tipo_movimiento = 'TRANSFERENCIA'
                            AND mt.estado_movimiento = 'ACEPTADA') AS transferencias_aceptadas,
                       EXISTS(SELECT 1 FROM MOVIMIENTO_ASIGNACION_ENTRADA mp
                          WHERE mp.id_entrada = e.id_entrada
                            AND mp.tipo_movimiento = 'TRANSFERENCIA'
                            AND mp.estado_movimiento = 'PENDIENTE') AS tiene_pendiente
                FROM MOVIMIENTO_ASIGNACION_ENTRADA m
                JOIN ENTRADA e ON e.id_entrada = m.id_entrada
                JOIN RESERVA_POR_VENTA r ON r.id_reserva_por_venta = e.id_reserva_por_venta
                JOIN SECTOR s ON s.id_sector = r.id_sector
                JOIN EVENTO_SECTOR es ON es.id_evento = r.id_evento AND es.id_sector = r.id_sector
                JOIN EVENTO ev ON ev.id_evento = r.id_evento
                JOIN SELECCION_NACIONAL sl ON sl.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL sv ON sv.id_seleccion = ev.id_seleccion_visitante
                JOIN ESTADIO est ON est.id_estadio = ev.id_estadio
                WHERE """ + " " + TitularidadDao.COND_VIGENTE_EFECTIVO
                + " AND (" + TitularidadDao.EXPR_TITULAR_VIGENTE + ") = ?"
                + " ORDER BY ev.fecha_hora_inicio, e.id_entrada";

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }

            List<Map<String, Object>> entradas = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, idUsuario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String estado = rs.getString("estado_entrada");
                        LocalDateTime inicio = rs.getTimestamp("fecha_hora_inicio") == null
                                ? null : rs.getTimestamp("fecha_hora_inicio").toLocalDateTime();
                        int aceptadas = rs.getInt("transferencias_aceptadas");
                        boolean pendiente = rs.getBoolean("tiene_pendiente");
                        boolean futuro = inicio != null && inicio.isAfter(LocalDateTime.now());
                        boolean transferible = "EMITIDA".equals(estado) && futuro && !pendiente && aceptadas < 3;

                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("idEntrada", rs.getInt("id_entrada"));
                        e.put("estadoEntrada", estado);
                        e.put("fechaEmision", str(rs.getTimestamp("fecha_emision")));
                        e.put("idEvento", rs.getInt("id_evento"));
                        e.put("idSector", rs.getInt("id_sector"));
                        e.put("nombreSector", rs.getString("nombre_sector"));
                        e.put("precio", rs.getBigDecimal("precio_entrada"));
                        e.put("local", rs.getString("local"));
                        e.put("visitante", rs.getString("visitante"));
                        e.put("estadio", rs.getString("nombre_estadio"));
                        e.put("ciudad", rs.getString("ciudad"));
                        e.put("fechaHoraInicio", str(rs.getTimestamp("fecha_hora_inicio")));
                        e.put("transferenciasAceptadas", aceptadas);
                        e.put("tienePendiente", pendiente);
                        e.put("transferible", transferible);
                        entradas.add(e);
                    }
                }
            }

            return ResponseEntity.ok(entradas);

        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_MIS_ENTRADAS",
                    "No se pudieron cargar las entradas");
        }
    }

    @GetMapping("/entradas/{id}/historial")
    public ResponseEntity<?> historial(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("id") int idEntrada) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        String sql = """
                SELECT m.nro_movimiento, m.tipo_movimiento, m.estado_movimiento,
                       m.fecha_solicitud, m.fecha_respuesta, m.fecha_desde, m.fecha_hasta,
                       uo.correo AS origen_correo, uo.nombre AS origen_nombre, uo.apellido AS origen_apellido,
                       ud.correo AS dest_correo, ud.nombre AS dest_nombre, ud.apellido AS dest_apellido
                FROM MOVIMIENTO_ASIGNACION_ENTRADA m
                JOIN USUARIO_GENERAL uo ON uo.id_usuario = m.id_usuario_titular_origen
                LEFT JOIN USUARIO_GENERAL ud ON ud.id_usuario = m.id_usuario_destinatario
                WHERE m.id_entrada = ?
                ORDER BY m.nro_movimiento
                """;

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }

            // Autorizacion: el solicitante debe aparecer en la cadena (o ser admin).
            if (!apareceEnCadena(connection, idEntrada, idUsuario) && !RoleGuard.esAdmin(connection, idUsuario)) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "SIN_ACCESO_ENTRADA",
                        "No tenes acceso al historial de esta entrada");
            }

            List<Map<String, Object>> chain = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, idEntrada);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> mov = new LinkedHashMap<>();
                        mov.put("nroMovimiento", rs.getInt("nro_movimiento"));
                        mov.put("tipo", rs.getString("tipo_movimiento"));
                        mov.put("estado", rs.getString("estado_movimiento"));
                        mov.put("origen", nombreCorreo(rs.getString("origen_nombre"), rs.getString("origen_apellido"), rs.getString("origen_correo")));
                        mov.put("destinatario", rs.getString("dest_correo") == null ? null
                                : nombreCorreo(rs.getString("dest_nombre"), rs.getString("dest_apellido"), rs.getString("dest_correo")));
                        mov.put("fechaSolicitud", str(rs.getTimestamp("fecha_solicitud")));
                        mov.put("fechaRespuesta", str(rs.getTimestamp("fecha_respuesta")));
                        mov.put("fechaDesde", str(rs.getTimestamp("fecha_desde")));
                        mov.put("fechaHasta", str(rs.getTimestamp("fecha_hasta")));
                        chain.add(mov);
                    }
                }
            }

            if (chain.isEmpty()) {
                return ApiResponses.error(HttpStatus.NOT_FOUND, "ENTRADA_INEXISTENTE",
                        "La entrada no existe o no tiene movimientos");
            }

            return ResponseEntity.ok(chain);

        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_HISTORIAL",
                    "No se pudo cargar el historial de la entrada");
        }
    }

    @GetMapping("/entradas/{id}/qr")
    public ResponseEntity<Map<String, Object>> qr(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("id") int idEntrada) {

        VerifiedFirebaseToken token;
        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());
            if (idUsuario == null) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no esta registrado en el sistema");
            }
            // Ver el QR equivale a poseer la credencial: solo el titular vigente, sin bypass de admin.
            if (!TitularidadDao.esTitularVigente(connection, idEntrada, idUsuario)) {
                return ApiResponses.error(HttpStatus.FORBIDDEN, "NO_ES_TITULAR",
                        "Solo el titular vigente puede ver el QR de la entrada");
            }

            String estadoEntrada;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT estado_entrada FROM ENTRADA WHERE id_entrada = ?")) {
                ps.setInt(1, idEntrada);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ApiResponses.error(HttpStatus.NOT_FOUND, "ENTRADA_INEXISTENTE", "La entrada no existe");
                    }
                    estadoEntrada = rs.getString(1);
                }
            }

            String semilla = semillaQr(connection, idEntrada);
            if (semilla == null) {
                // Backfill: entradas emitidas antes de tener credencial.
                semilla = QrTokenService.nuevaSemilla();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO CREDENCIAL_QR (id_entrada, semilla_qr, nonce_qr, firma_digital) VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, idEntrada);
                    ps.setString(2, semilla);
                    ps.setString(3, QrTokenService.nuevoNonce());
                    ps.setString(4, QrTokenService.firma(idEntrada, semilla));
                    ps.executeUpdate();
                }
            }

            Map<String, Object> r = ApiResponses.base("OK", "QR_OK");
            r.put("idEntrada", idEntrada);
            r.put("estadoEntrada", estadoEntrada);
            if ("EMITIDA".equals(estadoEntrada)) {
                long ventana = QrTokenService.ventanaActual();
                r.put("token", QrTokenService.token(idEntrada, semilla, ventana));
                r.put("window", ventana);
                r.put("expiraEnSegundos", QrTokenService.segundosParaExpirar());
            } else {
                r.put("token", null);
                r.put("message", "La entrada no esta vigente (estado " + estadoEntrada + ")");
            }
            return ResponseEntity.ok(r);

        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_QR",
                    "No se pudo generar el QR de la entrada");
        }
    }

    private static String semillaQr(Connection connection, int idEntrada) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT semilla_qr FROM CREDENCIAL_QR WHERE id_entrada = ?")) {
            ps.setInt(1, idEntrada);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static boolean apareceEnCadena(Connection connection, int idEntrada, int idUsuario) throws SQLException {
        String sql = """
                SELECT 1 FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND (id_usuario_titular_origen = ? OR id_usuario_destinatario = ?)
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, idEntrada);
            ps.setInt(2, idUsuario);
            ps.setInt(3, idUsuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String nombreCorreo(String nombre, String apellido, String correo) {
        String n = ((nombre == null ? "" : nombre) + " " + (apellido == null ? "" : apellido)).trim();
        return n.isEmpty() ? correo : n + " (" + correo + ")";
    }

    private static String str(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }
}
