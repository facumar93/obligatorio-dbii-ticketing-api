package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Derivacion de la titularidad vigente de una ENTRADA.
 *
 * INVARIANTE UNICO del sistema (usado por mis-entradas, historial, qr y validar):
 * el titular actual es el del movimiento "efectivo" con fecha_hasta IS NULL, donde
 * efectivo = (COMPRA_INICIAL + CONFIRMADA, titular = origen) o
 *            (TRANSFERENCIA  + ACEPTADA,   titular = destinatario).
 *
 * Una TRANSFERENCIA PENDIENTE tambien tiene fecha_hasta NULL pero queda EXCLUIDA
 * por el filtro de estado, asi que no cambia la titularidad hasta ser aceptada.
 */
final class TitularidadDao {

    private TitularidadDao() {
        // Clase utilitaria: no se instancia.
    }

    /**
     * Condicion SQL reutilizable que identifica al movimiento vigente efectivo.
     * El alias de la tabla MOVIMIENTO_ASIGNACION_ENTRADA debe ser "m".
     */
    static final String COND_VIGENTE_EFECTIVO =
            "m.fecha_hasta IS NULL AND ("
            + "(m.tipo_movimiento = 'COMPRA_INICIAL' AND m.estado_movimiento = 'CONFIRMADA') OR "
            + "(m.tipo_movimiento = 'TRANSFERENCIA'  AND m.estado_movimiento = 'ACEPTADA'))";

    /** Expresion SQL que devuelve el id del titular vigente. Requiere alias "m". */
    static final String EXPR_TITULAR_VIGENTE =
            "CASE WHEN m.tipo_movimiento = 'COMPRA_INICIAL' "
            + "THEN m.id_usuario_titular_origen ELSE m.id_usuario_destinatario END";

    /** Id del titular vigente de la entrada, o null si no hay movimiento efectivo. */
    static Integer titularVigente(Connection connection, int idEntrada) throws SQLException {
        String sql = "SELECT " + EXPR_TITULAR_VIGENTE + " AS titular "
                + "FROM MOVIMIENTO_ASIGNACION_ENTRADA m "
                + "WHERE m.id_entrada = ? AND " + COND_VIGENTE_EFECTIVO + " LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int titular = resultSet.getInt("titular");
                    return resultSet.wasNull() ? null : titular;
                }
                return null;
            }
        }
    }

    static boolean esTitularVigente(Connection connection, int idEntrada, int idUsuario) throws SQLException {
        Integer titular = titularVigente(connection, idEntrada);
        return titular != null && titular == idUsuario;
    }

    /** Cantidad de transferencias ACEPTADAS de la entrada (tope de negocio: 3). */
    static int transferenciasAceptadas(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS aceptadas
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'ACEPTADA'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("aceptadas");
            }
        }
    }

    /** Hay una solicitud de transferencia PENDIENTE sobre la entrada. */
    static boolean tienePendiente(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT 1
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'PENDIENTE'
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /** Proximo nro_movimiento para la entrada (PK parcial dependiente de id_entrada). */
    static int proximoNroMovimiento(Connection connection, int idEntrada) throws SQLException {
        String sql = "SELECT COALESCE(MAX(nro_movimiento), 0) + 1 AS proximo "
                + "FROM MOVIMIENTO_ASIGNACION_ENTRADA WHERE id_entrada = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("proximo");
            }
        }
    }
}
