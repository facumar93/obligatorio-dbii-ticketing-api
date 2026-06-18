package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
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
 * Identidad de dominio ya resuelta + roles del usuario autenticado.
 * El frontend lo consulta una vez tras el login para decidir que navegacion mostrar.
 * El backend igual re-chequea el rol en cada endpoint protegido.
 */
@RestController
public class RolController {

    @GetMapping("/me/roles")
    public ResponseEntity<Map<String, Object>> misRoles(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return ApiResponses.noAutorizado(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = RoleGuard.resolveIdUsuario(connection, token.uid());

            if (idUsuario == null) {
                Map<String, Object> respuesta = ApiResponses.base("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no esta registrado en el sistema");
                respuesta.put("authenticated", true);
                respuesta.put("registered", false);
                respuesta.put("uid", token.uid());
                respuesta.put("email", token.email());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            boolean esAdmin = RoleGuard.esAdmin(connection, idUsuario);
            boolean esValidador = RoleGuard.esValidador(connection, idUsuario);

            Map<String, Object> respuesta = ApiResponses.base("OK", "ROLES_OK");
            respuesta.put("authenticated", true);
            respuesta.put("registered", true);
            respuesta.put("idUsuario", idUsuario);
            respuesta.put("uid", token.uid());
            respuesta.put("email", token.email());
            respuesta.put("nombre", buscarNombreCompleto(connection, idUsuario));
            respuesta.put("estadoVerificacion", buscarEstadoVerificacion(connection, idUsuario));
            respuesta.put("esGeneral", true);
            respuesta.put("esAdmin", esAdmin);
            respuesta.put("esValidador", esValidador);
            respuesta.put("adminPaisesSede", esAdmin
                    ? RoleGuard.paisesSedeAdminVigentes(connection, idUsuario)
                    : java.util.List.of());

            return ResponseEntity.ok(respuesta);

        } catch (SQLException | IllegalStateException e) {
            return ApiResponses.error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_ROLES",
                    "No se pudieron resolver los roles del usuario");
        }
    }

    private static String buscarNombreCompleto(Connection connection, int idUsuario) throws SQLException {
        String sql = "SELECT nombre, apellido FROM USUARIO_GENERAL WHERE id_usuario = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return (resultSet.getString("nombre") + " " + resultSet.getString("apellido")).trim();
                }
                return null;
            }
        }
    }

    private static String buscarEstadoVerificacion(Connection connection, int idUsuario) throws SQLException {
        String sql = "SELECT estado_verificacion FROM USUARIO_GENERAL WHERE id_usuario = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("estado_verificacion") : null;
            }
        }
    }
}
