package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class UsuarioController {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @GetMapping("/usuarios")
    public List<Map<String, Object>> listarUsuarios() {
        List<Map<String, Object>> usuarios = new ArrayList<>();

        String sql = """
            SELECT
                id_usuario,
                correo,
                pais_documento,
                tipo_documento,
                numero_documento,
                pais_direccion,
                localidad,
                calle,
                num_direccion,
                codigo_postal,
                estado,
                fecha_alta
            FROM USUARIO_GENERAL
            ORDER BY id_usuario
            """;

        try (
            Connection connection = DbConnectionFactory.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)
        ) {
            while (resultSet.next()) {
                usuarios.add(Map.ofEntries(
                    Map.entry("idUsuario", resultSet.getInt("id_usuario")),
                    Map.entry("correo", resultSet.getString("correo").trim()),
                    Map.entry("paisDocumento", resultSet.getString("pais_documento").trim()),
                    Map.entry("tipoDocumento", resultSet.getString("tipo_documento").trim()),
                    Map.entry("numeroDocumento", resultSet.getString("numero_documento").trim()),
                    Map.entry("paisDireccion", getStringOrEmpty(resultSet, "pais_direccion")),
                    Map.entry("localidad", getStringOrEmpty(resultSet, "localidad")),
                    Map.entry("calle", getStringOrEmpty(resultSet, "calle")),
                    Map.entry("numDireccion", getStringOrEmpty(resultSet, "num_direccion")),
                    Map.entry("codigoPostal", getStringOrEmpty(resultSet, "codigo_postal")),
                    Map.entry("estado", resultSet.getString("estado").trim()),
                    Map.entry("fechaAlta", resultSet.getTimestamp("fecha_alta").toString())
                ));
            }

            return usuarios;

        } catch (Exception e) {
            return List.of(Map.of(
                "status", "ERROR",
                "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/usuarios")
    public ResponseEntity<Map<String, Object>> crearUsuario(@RequestBody CrearUsuarioRequest request) {
        if (isBlank(request.correo) ||
            isBlank(request.password) ||
            isBlank(request.paisDocumento) ||
            isBlank(request.tipoDocumento) ||
            isBlank(request.numeroDocumento)) {

            return ResponseEntity
                .badRequest()
                .body(Map.of(
                    "status", "ERROR",
                    "message", "Faltan datos obligatorios del usuario"
                ));
        }

        String sql = """
            INSERT INTO USUARIO_GENERAL (
                correo,
                password_hash,
                pais_documento,
                tipo_documento,
                numero_documento,
                pais_direccion,
                localidad,
                calle,
                num_direccion,
                codigo_postal
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String correoNormalizado = request.correo.trim().toLowerCase();

        try (
            Connection connection = DbConnectionFactory.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, correoNormalizado);
            statement.setString(2, PASSWORD_ENCODER.encode(request.password));
            statement.setString(3, request.paisDocumento.trim().toUpperCase());
            statement.setString(4, request.tipoDocumento.trim().toUpperCase());
            statement.setString(5, request.numeroDocumento.trim());
            statement.setString(6, emptyToNullUpper(request.paisDireccion));
            statement.setString(7, emptyToNull(request.localidad));
            statement.setString(8, emptyToNull(request.calle));
            statement.setString(9, emptyToNull(request.numDireccion));
            statement.setString(10, emptyToNull(request.codigoPostal));

            statement.executeUpdate();

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                    "status", "OK",
                    "message", "Usuario creado correctamente",
                    "correo", correoNormalizado
                ));

        } catch (Exception e) {
            String message = e.getMessage();

            if (esErrorDuplicado(e)) {
                return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                        "status", "ERROR",
                        "message", "Ya existe un usuario con ese correo o documento"
                    ));
            }

            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "status", "ERROR",
                    "message", message == null ? "Error inesperado al crear usuario" : message
                ));
        }
    }

    private boolean esErrorDuplicado(Exception e) {
        if (e == null) {
            return false;
        }
        if (e instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            int errorCode = sqlException.getErrorCode();

            return "23505".equals(sqlState) ||   // Db2: unique violation
                   "23000".equals(sqlState) ||   // MySQL: integrity constraint violation
                   errorCode == 1062;            // MySQL: duplicate entry
        }

        String message = e.getMessage();

        return message != null &&
               (message.contains("SQLSTATE=23505") ||
                message.contains("SQLSTATE=23000") ||
                message.contains("Duplicate entry") ||
                message.contains("1062"));
    }

    private String getStringOrEmpty(ResultSet resultSet, String columnName) throws Exception {
        String value = resultSet.getString(columnName);
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String emptyToNullUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public static class CrearUsuarioRequest {
        public String correo;
        public String password;
        public String paisDocumento;
        public String tipoDocumento;
        public String numeroDocumento;
        public String paisDireccion;
        public String localidad;
        public String calle;
        public String numDireccion;
        public String codigoPostal;
    }
}