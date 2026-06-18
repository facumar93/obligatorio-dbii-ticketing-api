package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Helpers de respuesta compartidos por los controllers de escritura.
 *
 * Centraliza el envelope status/code/message/timestamp para no repetirlo
 * (y no desincronizarlo) en cada controller. Los listados de solo lectura
 * siguen devolviendo arrays crudos (estilo /paises), no este envelope.
 */
final class ApiResponses {

    private ApiResponses() {
        // Clase utilitaria: no se instancia.
    }

    /** Mapa base mutable con status, code y timestamp. El caller agrega message y extras. */
    static Map<String, Object> base(String status, String code) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", status);
        respuesta.put("code", code);
        respuesta.put("timestamp", OffsetDateTime.now().toString());
        return respuesta;
    }

    /** Respuesta de error con el envelope estandar. No expone SQL ni detalles internos. */
    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String mensaje) {
        Map<String, Object> respuesta = base("ERROR", code);
        respuesta.put("message", mensaje);
        return ResponseEntity.status(status).body(respuesta);
    }

    /** Atajo para el 401 de header/token Firebase invalido. */
    static ResponseEntity<Map<String, Object>> noAutorizado(String code, String mensaje) {
        return error(HttpStatus.UNAUTHORIZED, code, mensaje);
    }

    static void rollbackSilencioso(Connection connection) {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // No se expone el detalle al cliente.
        }
    }
}
