package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolucion de identidad de dominio y chequeos de rol.
 *
 * No hay columna de rol en USUARIO_GENERAL: los roles se derivan de la
 * existencia de filas en USUARIO_ADMINISTRADOR / USUARIO_DE_VALIDACION y, para
 * admin, de una asignacion vigente en ASIGNACION_ADMIN_PAIS_SEDE. Esta clase
 * concentra esa logica para que la frontera de seguridad viva en un solo lugar.
 */
final class RoleGuard {

    private RoleGuard() {
        // Clase utilitaria: no se instancia.
    }

    /** Devuelve el id_usuario de dominio para un firebase_uid, o null si no esta registrado. */
    static Integer resolveIdUsuario(Connection connection, String firebaseUid) throws SQLException {
        String sql = "SELECT id_usuario FROM USUARIO_GENERAL WHERE firebase_uid = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("id_usuario") : null;
            }
        }
    }

    static boolean esAdmin(Connection connection, int idUsuario) throws SQLException {
        return existe(connection,
                "SELECT 1 FROM USUARIO_ADMINISTRADOR WHERE id_usuario = ?",
                idUsuario);
    }

    static boolean esValidador(Connection connection, int idUsuario) throws SQLException {
        return existe(connection,
                "SELECT 1 FROM USUARIO_DE_VALIDACION WHERE id_usuario = ?",
                idUsuario);
    }

    /**
     * Asignacion administrativa vigente del admin para el pais sede al que pertenece
     * el estadio dado. Devuelve id_asignacion (sirve para autorizar y como FK
     * id_asignacion_alta del EVENTO) o null si no tiene jurisdiccion vigente.
     */
    static Integer asignacionVigentePara(Connection connection, int idAdmin, int idEstadio) throws SQLException {
        String sql = """
                SELECT a.id_asignacion
                FROM ASIGNACION_ADMIN_PAIS_SEDE a
                JOIN ESTADIO e ON e.id_pais_sede = a.id_pais_sede
                WHERE a.id_usuario_admin = ?
                  AND e.id_estadio = ?
                  AND a.fecha_hasta IS NULL
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idAdmin);
            statement.setInt(2, idEstadio);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("id_asignacion") : null;
            }
        }
    }

    /** Paises sede que el admin administra de forma vigente (para /me/roles). */
    static List<Map<String, Object>> paisesSedeAdminVigentes(Connection connection, int idAdmin) throws SQLException {
        String sql = """
                SELECT p.id_pais, p.codigo_iso, p.nombre
                FROM ASIGNACION_ADMIN_PAIS_SEDE a
                JOIN PAIS_SEDE ps ON ps.id_pais = a.id_pais_sede
                JOIN PAIS p ON p.id_pais = ps.id_pais
                WHERE a.id_usuario_admin = ?
                  AND a.fecha_hasta IS NULL
                ORDER BY p.nombre
                """;

        List<Map<String, Object>> resultado = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idAdmin);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("idPaisSede", resultSet.getInt("id_pais"));
                    fila.put("codigoIso", resultSet.getString("codigo_iso").trim());
                    fila.put("nombre", resultSet.getString("nombre"));
                    resultado.add(fila);
                }
            }
        }

        return resultado;
    }

    /**
     * Verifica que un validador opere una asignacion dispositivo-validador concreta:
     * la asignacion debe pertenecer a una vinculacion ACTIVA del propio validador
     * y no estar cancelada.
     */
    static boolean validadorOperaAsignacion(Connection connection, int idUsuarioValidador,
            int idAsignacionDispositivoValidador) throws SQLException {

        String sql = """
                SELECT 1
                FROM ASIGNACION_DISPOSITIVO_VALIDADOR adv
                JOIN VINCULACION_VALIDADOR_DISPOSITIVO v ON v.id_vinculacion = adv.id_vinculacion
                WHERE adv.id_asignacion_dispositivo_validador = ?
                  AND v.id_usuario_validador = ?
                  AND v.estado_vinculacion = 'ACTIVA'
                  AND adv.fecha_cancelacion IS NULL
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idAsignacionDispositivoValidador);
            statement.setInt(2, idUsuarioValidador);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean existe(Connection connection, String sql, int idUsuario) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
