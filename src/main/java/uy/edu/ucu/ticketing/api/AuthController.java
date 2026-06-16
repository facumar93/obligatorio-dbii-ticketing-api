package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class AuthController {

    @GetMapping("/auth/firebase/me")
    public ResponseEntity<Map<String, Object>> obtenerIdentidadFirebase(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        try {
            VerifiedFirebaseToken token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);

            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("status", "OK");
            respuesta.put("provider", "firebase");
            respuesta.put("uid", token.uid());
            respuesta.put("email", token.email());
            respuesta.put("emailVerified", token.emailVerified());
            respuesta.put("name", token.name());
            respuesta.put("message", "Token Firebase verificado correctamente");
            respuesta.put("timestamp", OffsetDateTime.now().toString());

            return ResponseEntity.ok(respuesta);

        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }
    }

    @PostMapping("/auth/resolve")
    public ResponseEntity<Map<String, Object>> resolverUsuarioFirebase(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);

            try {
                UsuarioGeneral usuarioPorUid = buscarUsuarioPorFirebaseUid(connection, token.uid());

                if (usuarioPorUid != null) {
                    connection.commit();

                    return ResponseEntity.ok(respuestaResolve(
                            "LOGIN_OK",
                            "Usuario resuelto por firebase_uid",
                            token,
                            usuarioPorUid,
                            true
                    ));
                }

                if (!token.emailVerified() || esBlanco(token.email())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "EMAIL_NO_VERIFICADO");
                    respuesta.put("message", "No se puede reconciliar por correo si Firebase no informa un email verificado");
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);
                    respuesta.put("uid", token.uid());
                    respuesta.put("email", token.email());
                    respuesta.put("emailVerified", token.emailVerified());

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                UsuarioGeneral usuarioPorCorreo = buscarUsuarioPorCorreoSinFirebaseUidForUpdate(
                        connection,
                        token.email()
                );

                if (usuarioPorCorreo != null) {
                    int filasActualizadas = vincularFirebaseUid(
                            connection,
                            usuarioPorCorreo.idUsuario(),
                            token.uid()
                    );

                    if (filasActualizadas == 1) {
                        UsuarioGeneral usuarioVinculado = buscarUsuarioPorFirebaseUid(connection, token.uid());

                        if (usuarioVinculado == null) {
                            usuarioVinculado = usuarioPorCorreo.withFirebaseUid(token.uid());
                        }

                        connection.commit();

                        return ResponseEntity.ok(respuestaResolve(
                                "USUARIO_VINCULADO",
                                "Usuario preexistente vinculado a Firebase correctamente",
                                token,
                                usuarioVinculado,
                                true
                        ));
                    }

                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "CONFLICTO_VINCULACION");
                    respuesta.put("message", "No se pudo vincular el usuario porque la fila ya no estaba disponible");
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);
                    respuesta.put("uid", token.uid());
                    respuesta.put("email", token.email());
                    respuesta.put("emailVerified", token.emailVerified());

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (existeCorreoVinculadoAOtroFirebaseUid(connection, token.email(), token.uid())) {
                    connection.commit();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "CORREO_YA_VINCULADO");
                    respuesta.put("message", "El correo ya pertenece a otra identidad Firebase");
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);
                    respuesta.put("uid", token.uid());
                    respuesta.put("email", token.email());
                    respuesta.put("emailVerified", token.emailVerified());

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "REGISTRO_REQUERIDO");
                respuesta.put("message", "Usuario autenticado en Firebase, pero no registrado en USUARIO_GENERAL");
                respuesta.put("authenticated", true);
                respuesta.put("registered", false);
                respuesta.put("uid", token.uid());
                respuesta.put("email", token.email());
                respuesta.put("emailVerified", token.emailVerified());
                respuesta.put("name", token.name());

                return ResponseEntity.ok(respuesta);

            } catch (SQLIntegrityConstraintViolationException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "CONFLICTO_VINCULACION");
                respuesta.put("message", "No se pudo vincular el usuario por conflicto de unicidad");
                respuesta.put("authenticated", true);
                respuesta.put("registered", false);
                respuesta.put("uid", token.uid());
                respuesta.put("email", token.email());
                respuesta.put("emailVerified", token.emailVerified());

                return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_AUTH_RESOLVE");
                respuesta.put("message", "No se pudo resolver el usuario contra la base de datos");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_CONNECTION");
            respuesta.put("message", "No se pudo abrir la conexion a la base de datos");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> registrarUsuarioFirebase(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) RegistroUsuarioRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        if (!token.emailVerified() || esBlanco(token.email())) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "EMAIL_NO_VERIFICADO");
            respuesta.put("message", "Firebase debe informar un correo verificado para registrar el usuario");
            respuesta.put("authenticated", true);
            respuesta.put("registered", false);

            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
        }

        String errorValidacion = validarRegistro(request);

        if (errorValidacion != null) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "CAMPOS_OBLIGATORIOS_FALTANTES");
            respuesta.put("message", errorValidacion);
            respuesta.put("authenticated", true);
            respuesta.put("registered", false);

            return ResponseEntity.badRequest().body(respuesta);
        }

        String sql = """
                INSERT INTO USUARIO_GENERAL (
                    firebase_uid,
                    correo,
                    nombre,
                    apellido,
                    id_pais_documento,
                    id_tipo_documento,
                    numero_documento,
                    id_pais_direccion,
                    localidad,
                    calle,
                    numero_direccion,
                    codigo_postal,
                    estado_verificacion
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDIENTE')
                """;

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            )) {
                DatosDocumento datosDocumento = buscarDatosDocumento(
                        connection,
                        request.id_pais_documento,
                        request.id_tipo_documento
                );

                if (datosDocumento == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "DATOS_CATALOGO_INVALIDOS");
                    respuesta.put("message", "El pais o tipo de documento seleccionado no existe");
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);

                    return ResponseEntity.badRequest().body(respuesta);
                }

                String errorDocumento = validarDocumento(
                        datosDocumento.codigoPais(),
                        datosDocumento.codigoTipoDocumento(),
                        request.numero_documento
                );

                if (errorDocumento != null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "DOCUMENTO_INVALIDO");
                    respuesta.put("message", errorDocumento);
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);

                    return ResponseEntity.badRequest().body(respuesta);
                }

                statement.setString(1, token.uid());
                statement.setString(2, token.email().trim());
                statement.setString(3, request.nombre.trim());
                statement.setString(4, request.apellido.trim());
                statement.setInt(5, request.id_pais_documento);
                statement.setInt(6, request.id_tipo_documento);
                statement.setString(7, request.numero_documento.trim());
                statement.setInt(8, request.id_pais_direccion);
                statement.setString(9, request.localidad.trim());
                statement.setString(10, request.calle.trim());
                statement.setString(11, request.numero_direccion.trim());
                statement.setString(12, request.codigo_postal.trim());

                statement.executeUpdate();

                int idUsuario;

                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    if (!generatedKeys.next()) {
                        throw new SQLException("No se obtuvo el id_usuario generado");
                    }

                    idUsuario = generatedKeys.getInt(1);
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "REGISTRO_OK");
                respuesta.put("message", "Usuario registrado correctamente");
                respuesta.put("authenticated", true);
                respuesta.put("registered", true);
                respuesta.put("idUsuario", idUsuario);
                respuesta.put("correo", token.email());
                respuesta.put("estadoVerificacion", "PENDIENTE");

                return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);

            } catch (SQLIntegrityConstraintViolationException e) {
                rollbackSilencioso(connection);

                if (e.getErrorCode() == 1062) {
                    Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_YA_EXISTE");
                    respuesta.put("message", "Ya existe un usuario con ese correo, identidad Firebase o documento");
                    respuesta.put("authenticated", true);
                    respuesta.put("registered", false);

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                Map<String, Object> respuesta = respuestaBase("ERROR", "DATOS_CATALOGO_INVALIDOS");
                respuesta.put("message", "El pais o tipo de documento seleccionado no existe");
                respuesta.put("authenticated", true);
                respuesta.put("registered", false);

                return ResponseEntity.badRequest().body(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_AUTH_REGISTER");
                respuesta.put("message", "No se pudo registrar el usuario en la base de datos");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_CONNECTION");
            respuesta.put("message", "No se pudo abrir la conexion a la base de datos");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static DatosDocumento buscarDatosDocumento(
            Connection connection,
            int idPais,
            int idTipoDocumento
    ) throws SQLException {

        String sql = """
                SELECT p.codigo_iso, td.codigo
                FROM PAIS p
                CROSS JOIN TIPO_DOCUMENTO td
                WHERE p.id_pais = ?
                  AND td.id_tipo_documento = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idPais);
            statement.setInt(2, idTipoDocumento);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new DatosDocumento(
                            resultSet.getString("codigo_iso"),
                            resultSet.getString("codigo")
                    );
                }

                return null;
            }
        }
    }

    private static UsuarioGeneral buscarUsuarioPorFirebaseUid(Connection connection, String firebaseUid)
            throws SQLException {

        String sql = """
                SELECT
                    id_usuario,
                    firebase_uid,
                    correo,
                    nombre,
                    apellido,
                    estado_verificacion,
                    fecha_registro
                FROM USUARIO_GENERAL
                WHERE firebase_uid = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return mapearUsuarioGeneral(resultSet);
                }

                return null;
            }
        }
    }

    private static UsuarioGeneral buscarUsuarioPorCorreoSinFirebaseUidForUpdate(
            Connection connection,
            String correo
    ) throws SQLException {

        String sql = """
                SELECT
                    id_usuario,
                    firebase_uid,
                    correo,
                    nombre,
                    apellido,
                    estado_verificacion,
                    fecha_registro
                FROM USUARIO_GENERAL
                WHERE correo = ?
                  AND firebase_uid IS NULL
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, correo);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return mapearUsuarioGeneral(resultSet);
                }

                return null;
            }
        }
    }

    private static boolean existeCorreoVinculadoAOtroFirebaseUid(
            Connection connection,
            String correo,
            String firebaseUid
    ) throws SQLException {

        String sql = """
                SELECT 1
                FROM USUARIO_GENERAL
                WHERE correo = ?
                  AND firebase_uid IS NOT NULL
                  AND firebase_uid <> ?
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, correo);
            statement.setString(2, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static int vincularFirebaseUid(Connection connection, int idUsuario, String firebaseUid)
            throws SQLException {

        String sql = """
                UPDATE USUARIO_GENERAL
                SET firebase_uid = ?
                WHERE id_usuario = ?
                  AND firebase_uid IS NULL
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);
            statement.setInt(2, idUsuario);

            return statement.executeUpdate();
        }
    }

    private static UsuarioGeneral mapearUsuarioGeneral(ResultSet resultSet) throws SQLException {
        return new UsuarioGeneral(
                resultSet.getInt("id_usuario"),
                resultSet.getString("firebase_uid"),
                resultSet.getString("correo"),
                resultSet.getString("nombre"),
                resultSet.getString("apellido"),
                resultSet.getString("estado_verificacion"),
                timestampAString(resultSet.getTimestamp("fecha_registro"))
        );
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(String code, String mensaje) {
        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", mensaje);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(respuesta);
    }

    private static Map<String, Object> respuestaResolve(
            String code,
            String mensaje,
            VerifiedFirebaseToken token,
            UsuarioGeneral usuario,
            boolean registered
    ) {
        Map<String, Object> respuesta = respuestaBase("OK", code);
        respuesta.put("message", mensaje);
        respuesta.put("authenticated", true);
        respuesta.put("registered", registered);
        respuesta.put("uid", token.uid());
        respuesta.put("email", token.email());
        respuesta.put("emailVerified", token.emailVerified());
        respuesta.put("name", token.name());

        Map<String, Object> usuarioMap = new LinkedHashMap<>();
        usuarioMap.put("idUsuario", usuario.idUsuario());
        usuarioMap.put("firebaseUid", usuario.firebaseUid());
        usuarioMap.put("correo", usuario.correo());
        usuarioMap.put("nombre", usuario.nombre());
        usuarioMap.put("apellido", usuario.apellido());
        usuarioMap.put("estadoVerificacion", usuario.estadoVerificacion());
        usuarioMap.put("fechaRegistro", usuario.fechaRegistro());

        respuesta.put("usuario", usuarioMap);

        return respuesta;
    }

    private static Map<String, Object> respuestaBase(String status, String code) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", status);
        respuesta.put("code", code);
        respuesta.put("timestamp", OffsetDateTime.now().toString());
        return respuesta;
    }

    private static void rollbackSilencioso(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // No se expone el detalle al cliente.
        }
    }

    private static String timestampAString(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toInstant().toString();
    }

    private static boolean esBlanco(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String validarDocumento(String codigoPais, String codigoTipo, String numeroDocumento) {
        String pais = codigoPais.trim().toUpperCase();
        String tipo = codigoTipo.trim().toUpperCase();
        String numero = numeroDocumento.trim();

        if (!"PAS".equals(tipo)
                && !("URY".equals(pais) && "CI".equals(tipo))
                && !("ARG".equals(pais) && "DNI".equals(tipo))) {
            return "La combinacion de pais y tipo de documento no es valida";
        }

        if ("CI".equals(tipo)) {
            if (!numero.matches("[0-9]{6,8}")) {
                return "La cedula uruguaya debe contener solo digitos y tener entre 6 y 8 digitos";
            }

            if (!cedulaUruguayaValida(numero)) {
                return "El digito verificador de la cedula uruguaya no es valido";
            }

            return null;
        }

        if ("DNI".equals(tipo) && !numero.matches("[0-9]{6,8}")) {
            return "El DNI debe contener solo digitos y tener entre 6 y 8 digitos";
        }

        if ("PAS".equals(tipo) && !numero.matches("[A-Za-z0-9]{6,12}")) {
            return "El pasaporte debe ser alfanumerico y tener entre 6 y 12 caracteres";
        }

        return null;
    }

    private static boolean cedulaUruguayaValida(String numeroDocumento) {
        String numero = numeroDocumento.trim();

        if (!numero.matches("[0-9]{6,8}")) {
            return false;
        }

        String base = numero.substring(0, numero.length() - 1);
        int verificadorInformado = Character.digit(numero.charAt(numero.length() - 1), 10);

        String baseNormalizada = "0".repeat(7 - base.length()) + base;

        int[] pesos = {2, 9, 8, 7, 6, 3, 4};
        int suma = 0;

        for (int i = 0; i < pesos.length; i++) {
            int digito = Character.digit(baseNormalizada.charAt(i), 10);
            suma += digito * pesos[i];
        }

        int resto = suma % 10;
        int verificadorEsperado = resto == 0 ? 0 : 10 - resto;

        return verificadorInformado == verificadorEsperado;
    }

    private static String validarRegistro(RegistroUsuarioRequest request) {
        if (request == null) {
            return "Falta el cuerpo con los datos de registro";
        }

        List<String> camposInvalidos = new ArrayList<>();

        agregarSiBlanco(camposInvalidos, "nombre", request.nombre);
        agregarSiBlanco(camposInvalidos, "apellido", request.apellido);
        agregarSiBlanco(camposInvalidos, "numero_documento", request.numero_documento);
        agregarSiIdInvalido(camposInvalidos, "id_pais_documento", request.id_pais_documento);
        agregarSiIdInvalido(camposInvalidos, "id_tipo_documento", request.id_tipo_documento);
        agregarSiIdInvalido(camposInvalidos, "id_pais_direccion", request.id_pais_direccion);
        agregarSiBlanco(camposInvalidos, "localidad", request.localidad);
        agregarSiBlanco(camposInvalidos, "calle", request.calle);
        agregarSiBlanco(camposInvalidos, "numero_direccion", request.numero_direccion);
        agregarSiBlanco(camposInvalidos, "codigo_postal", request.codigo_postal);

        if (camposInvalidos.isEmpty()) {
            return null;
        }

        return "Faltan datos obligatorios o son invalidos: " + String.join(", ", camposInvalidos);
    }

    private static void agregarSiBlanco(List<String> camposInvalidos, String nombreCampo, String valor) {
        if (esBlanco(valor)) {
            camposInvalidos.add(nombreCampo);
        }
    }

    private static void agregarSiIdInvalido(List<String> camposInvalidos, String nombreCampo, Integer valor) {
        if (valor == null || valor <= 0) {
            camposInvalidos.add(nombreCampo);
        }
    }

    public static class RegistroUsuarioRequest {
        public String nombre;
        public String apellido;
        public String numero_documento;
        public Integer id_pais_documento;
        public Integer id_tipo_documento;
        public Integer id_pais_direccion;
        public String localidad;
        public String calle;
        public String numero_direccion;
        public String codigo_postal;
    }

    private record DatosDocumento(
            String codigoPais,
            String codigoTipoDocumento
    ) {
    }

    private record UsuarioGeneral(
            int idUsuario,
            String firebaseUid,
            String correo,
            String nombre,
            String apellido,
            String estadoVerificacion,
            String fechaRegistro
    ) {
        UsuarioGeneral withFirebaseUid(String nuevoFirebaseUid) {
            return new UsuarioGeneral(
                    idUsuario,
                    nuevoFirebaseUid,
                    correo,
                    nombre,
                    apellido,
                    estadoVerificacion,
                    fechaRegistro
            );
        }
    }
}
