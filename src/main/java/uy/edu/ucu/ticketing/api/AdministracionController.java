package uy.edu.ucu.ticketing.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class AdministracionController {

    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("99999999.99");

    @GetMapping("/admin/contexto")
    public ResponseEntity<Map<String, Object>> obtenerContextoAdministrativo(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            UsuarioBasico usuario = buscarUsuarioPorFirebaseUid(connection, token.uid());

            if (usuario == null) {
                return respuestaError(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no existe en USUARIO_GENERAL");
            }

            boolean esAdministrador = esUsuarioAdministrador(connection, usuario.idUsuario());
            List<AsignacionVigente> asignaciones = esAdministrador
                    ? buscarAsignacionesVigentes(connection, usuario.idUsuario(), false)
                    : List.of();

            Map<String, Object> respuesta = respuestaBase("OK", "ADMIN_CONTEXTO_OK");
            respuesta.put("esAdministrador", esAdministrador);
            respuesta.put("idUsuario", usuario.idUsuario());
            respuesta.put("nombre", usuario.nombre());
            respuesta.put("apellido", usuario.apellido());
            respuesta.put("asignacionesVigentes", asignaciones.stream()
                    .map(AdministracionController::asignacionAMap)
                    .toList());

            return ResponseEntity.ok(respuesta);

        } catch (SQLException | IllegalStateException e) {
            return respuestaError(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo consultar el contexto administrativo");
        }
    }

    @GetMapping("/admin/eventos/opciones")
    public ResponseEntity<Map<String, Object>> obtenerOpcionesAltaEvento(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            UsuarioBasico usuario = buscarUsuarioPorFirebaseUid(connection, token.uid());

            if (usuario == null) {
                return respuestaError(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                        "El usuario autenticado no existe en USUARIO_GENERAL");
            }

            if (!esUsuarioAdministrador(connection, usuario.idUsuario())) {
                return respuestaError(HttpStatus.FORBIDDEN, "USUARIO_NO_ADMINISTRADOR",
                        "El usuario autenticado no tiene rol de administrador");
            }

            List<AsignacionVigente> asignaciones = buscarAsignacionesVigentes(
                    connection,
                    usuario.idUsuario(),
                    false
            );

            if (asignaciones.isEmpty()) {
                return respuestaError(HttpStatus.FORBIDDEN, "ADMIN_SIN_ASIGNACION_VIGENTE",
                        "El administrador no tiene asignaciones vigentes");
            }

            Map<String, Object> respuesta = respuestaBase("OK", "OPCIONES_ADMIN_EVENTO_OK");
            respuesta.put("selecciones", buscarSelecciones(connection));
            respuesta.put("estadios", buscarEstadiosYSectoresPermitidos(connection, usuario.idUsuario()));
            return ResponseEntity.ok(respuesta);

        } catch (SQLException | IllegalStateException e) {
            return respuestaError(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudieron consultar las opciones administrativas");
        }
    }
    @PostMapping("/admin/eventos")
    public ResponseEntity<Map<String, Object>> crearEvento(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) CrearEventoRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        ValidacionInput validacion = validarInputAlta(request);

        if (validacion.error() != null) {
            return respuestaError(HttpStatus.BAD_REQUEST, "INPUT_INVALIDO", validacion.error());
        }

        List<SectorAltaRequest> sectoresOrdenados = request.sectores.stream()
                .sorted(Comparator.comparingInt(sector -> sector.idSector))
                .toList();

        try (Connection connection = DbConnectionFactory.getConnection()) {
            connection.setAutoCommit(false);

            try {
                UsuarioBasico usuario = buscarUsuarioPorFirebaseUid(connection, token.uid());

                if (usuario == null) {
                    connection.rollback();
                    return respuestaError(HttpStatus.FORBIDDEN, "USUARIO_NO_REGISTRADO",
                            "El usuario autenticado no existe en USUARIO_GENERAL");
                }

                if (!esUsuarioAdministrador(connection, usuario.idUsuario())) {
                    connection.rollback();
                    return respuestaError(HttpStatus.FORBIDDEN, "USUARIO_NO_ADMINISTRADOR",
                            "El usuario autenticado no tiene rol de administrador");
                }

                EstadioBloqueado estadio = bloquearEstadio(connection, request.idEstadio);

                if (estadio == null) {
                    connection.rollback();
                    return respuestaError(HttpStatus.NOT_FOUND, "ESTADIO_NO_ENCONTRADO",
                            "El estadio indicado no existe");
                }

                List<AsignacionVigente> asignaciones = buscarAsignacionesVigentes(
                        connection,
                        usuario.idUsuario(),
                        true
                );

                if (asignaciones.isEmpty()) {
                    connection.rollback();
                    return respuestaError(HttpStatus.FORBIDDEN, "ADMIN_SIN_ASIGNACION_VIGENTE",
                            "El administrador no tiene asignaciones vigentes");
                }

                List<AsignacionVigente> asignacionesDelPais = asignaciones.stream()
                        .filter(asignacion -> asignacion.idPaisSede() == estadio.idPaisSede())
                        .toList();

                if (asignacionesDelPais.isEmpty()) {
                    connection.rollback();
                    return respuestaError(HttpStatus.FORBIDDEN, "ESTADIO_FUERA_DE_JURISDICCION",
                            "El estadio no pertenece a una jurisdiccion vigente del administrador");
                }

                if (asignacionesDelPais.size() > 1) {
                    connection.rollback();
                    return respuestaError(HttpStatus.CONFLICT, "ASIGNACION_ADMIN_INCONSISTENTE",
                            "Existe mas de una asignacion vigente para el administrador y el pais sede");
                }

                if (!inicioEsFuturo(connection, validacion.fechaInicio())) {
                    connection.rollback();
                    return respuestaError(HttpStatus.BAD_REQUEST, "EVENTO_NO_FUTURO",
                            "La fecha de inicio debe ser posterior al momento actual de la base");
                }

                if (!existenSelecciones(connection, request.idSeleccionLocal, request.idSeleccionVisitante)) {
                    connection.rollback();
                    return respuestaError(HttpStatus.BAD_REQUEST, "SELECCION_INVALIDA",
                            "Una o ambas selecciones indicadas no existen");
                }

                long capacidadTotalHabilitada = 0;

                for (SectorAltaRequest sectorRequest : sectoresOrdenados) {
                    SectorBloqueado sector = bloquearSector(connection, sectorRequest.idSector);

                    if (sector == null) {
                        connection.rollback();
                        return respuestaErrorConDato(HttpStatus.BAD_REQUEST, "SECTOR_NO_ENCONTRADO",
                                "Uno de los sectores indicados no existe",
                                "idSector", sectorRequest.idSector);
                    }

                    if (sector.idEstadio() != estadio.idEstadio()) {
                        connection.rollback();
                        return respuestaErrorConDato(HttpStatus.BAD_REQUEST,
                                "SECTOR_NO_PERTENECE_AL_ESTADIO",
                                "El sector indicado no pertenece al estadio del evento",
                                "idSector", sectorRequest.idSector);
                    }

                    if (sectorRequest.capacidadHabilitada > sector.capacidadMax()) {
                        connection.rollback();

                        Map<String, Object> respuesta = respuestaBase("ERROR", "CAPACIDAD_SECTOR_INVALIDA");
                        respuesta.put("message",
                                "La capacidad habilitada supera la capacidad maxima del sector");
                        respuesta.put("idSector", sectorRequest.idSector);
                        respuesta.put("capacidadMax", sector.capacidadMax());
                        return ResponseEntity.badRequest().body(respuesta);
                    }

                    capacidadTotalHabilitada += sectorRequest.capacidadHabilitada;
                }

                if (capacidadTotalHabilitada > estadio.capacidad()) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "CAPACIDAD_ESTADIO_SUPERADA");
                    respuesta.put("message",
                            "La suma de capacidades habilitadas supera la capacidad del estadio");
                    respuesta.put("capacidadEstadio", estadio.capacidad());
                    respuesta.put("capacidadSolicitada", capacidadTotalHabilitada);
                    return ResponseEntity.badRequest().body(respuesta);
                }

                if (existeSolapamiento(
                        connection,
                        estadio.idEstadio(),
                        validacion.fechaInicio(),
                        validacion.fechaFin()
                )) {
                    connection.rollback();
                    return respuestaError(HttpStatus.CONFLICT, "HORARIO_ESTADIO_OCUPADO",
                            "Existe otro evento en el estadio dentro del horario solicitado");
                }

                int idAsignacion = asignacionesDelPais.getFirst().idAsignacion();
                int idEvento = insertarEvento(
                        connection,
                        request,
                        validacion.fechaInicio(),
                        validacion.fechaFin(),
                        idAsignacion
                );

                insertarEventoSectores(connection, idEvento, sectoresOrdenados);
                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "EVENTO_CREADO");
                respuesta.put("idEvento", idEvento);
                respuesta.put("idAsignacionAlta", idAsignacion);
                respuesta.put("sectoresHabilitados", sectoresOrdenados.size());
                return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);

            } catch (SQLIntegrityConstraintViolationException e) {
                rollbackSilencioso(connection);
                return respuestaError(HttpStatus.BAD_REQUEST, "DATOS_EVENTO_INVALIDOS",
                        "Los datos del evento no cumplen las restricciones de la base");

            } catch (SQLException e) {
                rollbackSilencioso(connection);
                return respuestaError(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CREAR_EVENTO",
                        "No se pudo crear el evento");
            }

        } catch (SQLException | IllegalStateException e) {
            return respuestaError(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_DB_CONNECTION",
                    "No se pudo abrir la conexion a la base de datos");
        }
    }
    private static ValidacionInput validarInputAlta(CrearEventoRequest request) {
        if (request == null) {
            return ValidacionInput.error("El body del evento es obligatorio");
        }

        if (request.idEstadio == null || request.idEstadio <= 0) {
            return ValidacionInput.error("idEstadio debe ser mayor a cero");
        }

        if (request.idSeleccionLocal == null || request.idSeleccionLocal <= 0
                || request.idSeleccionVisitante == null || request.idSeleccionVisitante <= 0) {
            return ValidacionInput.error("Las selecciones deben tener identificadores validos");
        }

        if (request.idSeleccionLocal.equals(request.idSeleccionVisitante)) {
            return ValidacionInput.error("Las selecciones local y visitante deben ser distintas");
        }

        LocalDateTime fechaInicio;
        LocalDateTime fechaFin;

        try {
            fechaInicio = LocalDateTime.parse(
                    request.fechaHoraInicio == null ? "" : request.fechaHoraInicio.trim()
            );
            fechaFin = LocalDateTime.parse(
                    request.fechaHoraFin == null ? "" : request.fechaHoraFin.trim()
            );
        } catch (DateTimeParseException e) {
            return ValidacionInput.error(
                    "Las fechas deben tener formato ISO local, por ejemplo 2026-07-15T16:00:00"
            );
        }

        if (!fechaFin.isAfter(fechaInicio)) {
            return ValidacionInput.error("fechaHoraFin debe ser posterior a fechaHoraInicio");
        }

        if (request.sectores == null || request.sectores.isEmpty()) {
            return ValidacionInput.error("El evento debe habilitar al menos un sector");
        }

        Set<Integer> idsSectores = new HashSet<>();

        for (SectorAltaRequest sector : request.sectores) {
            if (sector == null) {
                return ValidacionInput.error("Los sectores no pueden ser nulos");
            }

            if (sector.idSector == null || sector.idSector <= 0) {
                return ValidacionInput.error("Cada idSector debe ser mayor a cero");
            }

            if (!idsSectores.add(sector.idSector)) {
                return ValidacionInput.error("No se puede repetir un sector en el mismo evento");
            }

            if (sector.precioEntrada == null || sector.precioEntrada.compareTo(BigDecimal.ZERO) < 0) {
                return ValidacionInput.error("Cada precioEntrada debe ser mayor o igual a cero");
            }

            try {
                sector.precioEntrada.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                return ValidacionInput.error("precioEntrada admite como maximo dos decimales");
            }

            if (sector.precioEntrada.compareTo(PRECIO_MAXIMO) > 0) {
                return ValidacionInput.error("precioEntrada supera el maximo admitido por DECIMAL(10,2)");
            }

            if (sector.capacidadHabilitada == null || sector.capacidadHabilitada <= 0) {
                return ValidacionInput.error("Cada capacidadHabilitada debe ser mayor a cero");
            }
        }

        return new ValidacionInput(null, fechaInicio, fechaFin);
    }

    private static UsuarioBasico buscarUsuarioPorFirebaseUid(
            Connection connection,
            String firebaseUid
    ) throws SQLException {

        String sql = """
                SELECT id_usuario, nombre, apellido
                FROM USUARIO_GENERAL
                WHERE firebase_uid = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new UsuarioBasico(
                        resultSet.getInt("id_usuario"),
                        resultSet.getString("nombre"),
                        resultSet.getString("apellido")
                );
            }
        }
    }

    private static boolean esUsuarioAdministrador(Connection connection, int idUsuario)
            throws SQLException {

        String sql = """
                SELECT 1
                FROM USUARIO_ADMINISTRADOR
                WHERE id_usuario = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static List<AsignacionVigente> buscarAsignacionesVigentes(
            Connection connection,
            int idUsuario,
            boolean bloquear
    ) throws SQLException {

        String sql = """
                SELECT a.id_asignacion, a.id_pais_sede, p.nombre AS pais_sede
                FROM ASIGNACION_ADMIN_PAIS_SEDE a
                JOIN PAIS p ON p.id_pais = a.id_pais_sede
                WHERE a.id_usuario_admin = ?
                  AND a.fecha_desde <= NOW()
                  AND (a.fecha_hasta IS NULL OR a.fecha_hasta > NOW())
                ORDER BY a.id_asignacion
                """ + (bloquear ? " FOR UPDATE" : "");

        List<AsignacionVigente> asignaciones = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    asignaciones.add(new AsignacionVigente(
                            resultSet.getInt("id_asignacion"),
                            resultSet.getInt("id_pais_sede"),
                            resultSet.getString("pais_sede")
                    ));
                }
            }
        }

        return asignaciones;
    }
    private static List<Map<String, Object>> buscarSelecciones(Connection connection)
            throws SQLException {

        String sql = """
                SELECT id_seleccion, nombre
                FROM SELECCION_NACIONAL
                ORDER BY nombre, id_seleccion
                """;

        List<Map<String, Object>> selecciones = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                Map<String, Object> seleccion = new LinkedHashMap<>();
                seleccion.put("idSeleccion", resultSet.getInt("id_seleccion"));
                seleccion.put("nombre", resultSet.getString("nombre"));
                selecciones.add(seleccion);
            }
        }

        return selecciones;
    }

    private static List<Map<String, Object>> buscarEstadiosYSectoresPermitidos(
            Connection connection,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT DISTINCT
                    e.id_estadio,
                    e.nombre AS estadio,
                    e.ciudad,
                    e.capacidad,
                    e.id_pais_sede,
                    p.nombre AS pais_sede,
                    s.id_sector,
                    s.nombre_sector,
                    s.capacidad_max
                FROM ASIGNACION_ADMIN_PAIS_SEDE a
                JOIN ESTADIO e ON e.id_pais_sede = a.id_pais_sede
                JOIN PAIS p ON p.id_pais = e.id_pais_sede
                JOIN SECTOR s ON s.id_estadio = e.id_estadio
                WHERE a.id_usuario_admin = ?
                  AND a.fecha_desde <= NOW()
                  AND (a.fecha_hasta IS NULL OR a.fecha_hasta > NOW())
                ORDER BY e.nombre, e.id_estadio, s.nombre_sector, s.id_sector
                """;

        Map<Integer, Map<String, Object>> estadiosPorId = new LinkedHashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int idEstadio = resultSet.getInt("id_estadio");
                    Map<String, Object> estadio = estadiosPorId.get(idEstadio);

                    if (estadio == null) {
                        estadio = new LinkedHashMap<>();
                        estadio.put("idEstadio", idEstadio);
                        estadio.put("nombre", resultSet.getString("estadio"));
                        estadio.put("ciudad", resultSet.getString("ciudad"));
                        estadio.put("capacidad", resultSet.getInt("capacidad"));
                        estadio.put("idPaisSede", resultSet.getInt("id_pais_sede"));
                        estadio.put("paisSede", resultSet.getString("pais_sede"));
                        estadio.put("sectores", new ArrayList<Map<String, Object>>());
                        estadiosPorId.put(idEstadio, estadio);
                    }

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> sectores =
                            (List<Map<String, Object>>) estadio.get("sectores");

                    Map<String, Object> sector = new LinkedHashMap<>();
                    sector.put("idSector", resultSet.getInt("id_sector"));
                    sector.put("nombre", resultSet.getString("nombre_sector"));
                    sector.put("capacidadMax", resultSet.getInt("capacidad_max"));
                    sectores.add(sector);
                }
            }
        }

        return new ArrayList<>(estadiosPorId.values());
    }

    private static EstadioBloqueado bloquearEstadio(Connection connection, int idEstadio)
            throws SQLException {

        String sql = """
                SELECT id_estadio, id_pais_sede, capacidad
                FROM ESTADIO
                WHERE id_estadio = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEstadio);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new EstadioBloqueado(
                        resultSet.getInt("id_estadio"),
                        resultSet.getInt("id_pais_sede"),
                        resultSet.getInt("capacidad")
                );
            }
        }
    }

    private static SectorBloqueado bloquearSector(Connection connection, int idSector)
            throws SQLException {

        String sql = """
                SELECT id_sector, id_estadio, capacidad_max
                FROM SECTOR
                WHERE id_sector = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idSector);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new SectorBloqueado(
                        resultSet.getInt("id_sector"),
                        resultSet.getInt("id_estadio"),
                        resultSet.getInt("capacidad_max")
                );
            }
        }
    }
    private static boolean inicioEsFuturo(Connection connection, LocalDateTime fechaInicio)
            throws SQLException {

        String sql = "SELECT ? > NOW() AS inicio_futuro";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(fechaInicio));

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean("inicio_futuro");
            }
        }
    }

    private static boolean existenSelecciones(Connection connection, int idLocal, int idVisitante)
            throws SQLException {

        String sql = """
                SELECT COUNT(*) AS cantidad
                FROM SELECCION_NACIONAL
                WHERE id_seleccion IN (?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idLocal);
            statement.setInt(2, idVisitante);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("cantidad") == 2;
            }
        }
    }

    private static boolean existeSolapamiento(
            Connection connection,
            int idEstadio,
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin
    ) throws SQLException {

        String sql = """
                SELECT id_evento
                FROM EVENTO
                WHERE id_estadio = ?
                  AND fecha_hora_inicio < ?
                  AND fecha_hora_fin > ?
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEstadio);
            statement.setTimestamp(2, Timestamp.valueOf(fechaFin));
            statement.setTimestamp(3, Timestamp.valueOf(fechaInicio));

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static int insertarEvento(
            Connection connection,
            CrearEventoRequest request,
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin,
            int idAsignacion
    ) throws SQLException {

        String sql = """
                INSERT INTO EVENTO (
                    fecha_hora_inicio,
                    fecha_hora_fin,
                    id_estadio,
                    id_seleccion_local,
                    id_seleccion_visitante,
                    id_asignacion_alta
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setTimestamp(1, Timestamp.valueOf(fechaInicio));
            statement.setTimestamp(2, Timestamp.valueOf(fechaFin));
            statement.setInt(3, request.idEstadio);
            statement.setInt(4, request.idSeleccionLocal);
            statement.setInt(5, request.idSeleccionVisitante);
            statement.setInt(6, idAsignacion);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("No se obtuvo el id_evento generado");
                }

                return generatedKeys.getInt(1);
            }
        }
    }

    private static void insertarEventoSectores(
            Connection connection,
            int idEvento,
            List<SectorAltaRequest> sectores
    ) throws SQLException {

        String sql = """
                INSERT INTO EVENTO_SECTOR (
                    id_evento,
                    id_sector,
                    precio_entrada,
                    capacidad_habilitada
                ) VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SectorAltaRequest sector : sectores) {
                statement.setInt(1, idEvento);
                statement.setInt(2, sector.idSector);
                statement.setBigDecimal(3, sector.precioEntrada.setScale(2));
                statement.setInt(4, sector.capacidadHabilitada);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static Map<String, Object> asignacionAMap(AsignacionVigente asignacion) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("idAsignacion", asignacion.idAsignacion());
        respuesta.put("idPaisSede", asignacion.idPaisSede());
        respuesta.put("paisSede", asignacion.paisSede());
        return respuesta;
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(
            String code,
            String mensaje
    ) {
        return respuestaError(HttpStatus.UNAUTHORIZED, code, mensaje);
    }

    private static ResponseEntity<Map<String, Object>> respuestaError(
            HttpStatus status,
            String code,
            String mensaje
    ) {
        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", mensaje);
        return ResponseEntity.status(status).body(respuesta);
    }

    private static ResponseEntity<Map<String, Object>> respuestaErrorConDato(
            HttpStatus status,
            String code,
            String mensaje,
            String nombreDato,
            Object dato
    ) {
        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", mensaje);
        respuesta.put(nombreDato, dato);
        return ResponseEntity.status(status).body(respuesta);
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

    public static class CrearEventoRequest {
        public String fechaHoraInicio;
        public String fechaHoraFin;
        public Integer idEstadio;
        public Integer idSeleccionLocal;
        public Integer idSeleccionVisitante;
        public List<SectorAltaRequest> sectores;
    }

    public static class SectorAltaRequest {
        public Integer idSector;
        public BigDecimal precioEntrada;
        public Integer capacidadHabilitada;
    }

    private record UsuarioBasico(int idUsuario, String nombre, String apellido) {
    }

    private record AsignacionVigente(int idAsignacion, int idPaisSede, String paisSede) {
    }

    private record EstadioBloqueado(int idEstadio, int idPaisSede, int capacidad) {
    }

    private record SectorBloqueado(int idSector, int idEstadio, int capacidadMax) {
    }

    private record ValidacionInput(String error, LocalDateTime fechaInicio, LocalDateTime fechaFin) {
        static ValidacionInput error(String mensaje) {
            return new ValidacionInput(mensaje, null, null);
        }
    }
}