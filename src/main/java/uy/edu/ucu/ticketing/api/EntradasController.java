package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.InvalidAuthorizationException;
import uy.edu.ucu.ticketing.api.FirebaseTokenVerifier.VerifiedFirebaseToken;
import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class EntradasController {

    private static final int MAXIMO_TRANSFERENCIAS_ACEPTADAS = 3;
    private static final int MAXIMO_LARGO_CORREO = 120;

    @GetMapping("/entradas/mias")
    public ResponseEntity<?> listarMisEntradas(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idUsuario == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            ResultadoListadoEntradas resultado = buscarEntradasDelTitular(connection, idUsuario);

            if (resultado.titularidadInconsistente()) {
                return respuestaTitularidadInconsistente();
            }

            return ResponseEntity.ok(resultado.entradas());

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_ENTRADAS");
            respuesta.put("message", "No se pudieron cargar las entradas del usuario");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @GetMapping("/transferencias/recibidas")
    public ResponseEntity<?> listarTransferenciasRecibidas(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idUsuario == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            ResultadoTransferenciasRecibidas resultado = buscarTransferenciasRecibidas(connection, idUsuario);

            if (resultado.transferenciaInconsistente()) {
                return respuestaTransferenciaInconsistente();
            }

            return ResponseEntity.ok(resultado.transferencias());

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_TRANSFERENCIAS_RECIBIDAS");
            respuesta.put("message", "No se pudieron cargar las transferencias recibidas");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @GetMapping("/transferencias/enviadas")
    public ResponseEntity<?> listarTransferenciasEnviadas(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idUsuario = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idUsuario == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            ResultadoTransferenciasEnviadas resultado = buscarTransferenciasEnviadas(connection, idUsuario);

            if (resultado.transferenciaInconsistente()) {
                return respuestaTransferenciaInconsistente();
            }

            return ResponseEntity.ok(resultado.transferencias());

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_TRANSFERENCIAS_ENVIADAS");
            respuesta.put("message", "No se pudieron cargar las transferencias enviadas");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @PostMapping("/entradas/{idEntrada}/transferencias")
    public ResponseEntity<Map<String, Object>> solicitarTransferencia(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada,
            @RequestBody(required = false) SolicitarTransferenciaRequest request) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        String errorInput = validarInputTransferencia(idEntrada, request);

        if (errorInput != null) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", errorInput);

            return ResponseEntity.badRequest().body(respuesta);
        }

        String correoDestinatario = request.correoDestinatario.trim();

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idActor = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idActor == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                EntradaBloqueada entrada = bloquearEntrada(connection, idEntrada);

                if (entrada == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_ENCONTRADA");
                    respuesta.put("message", "La entrada solicitada no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (!"EMITIDA".equals(entrada.estadoEntrada())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_TRANSFERIBLE");
                    respuesta.put("message", "Solo se pueden transferir entradas en estado EMITIDA");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                List<MovimientoVigente> movimientosVigentes = buscarMovimientosVigentesBloqueados(
                        connection,
                        idEntrada
                );

                if (movimientosVigentes.size() != 1) {
                    connection.rollback();
                    return respuestaTitularidadInconsistente();
                }

                MovimientoVigente movimientoVigente = movimientosVigentes.get(0);

                if (movimientoVigente.idTitularActual() != idActor) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "ENTRADA_NO_PERTENECE_AL_USUARIO"
                    );
                    respuesta.put("message", "El usuario autenticado no es el titular vigente de la entrada");

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                UsuarioDestino destinatario = buscarUsuarioPorCorreo(connection, correoDestinatario);

                if (destinatario == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "DESTINATARIO_NO_REGISTRADO");
                    respuesta.put("message", "No existe un usuario registrado con el correo indicado");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (destinatario.idUsuario() == idActor) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TRANSFERENCIA_A_UNO_MISMO");
                    respuesta.put("message", "La entrada no se puede transferir a su titular actual");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (contarTransferenciasPendientes(connection, idEntrada) > 0) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "TRANSFERENCIA_PENDIENTE_EXISTENTE"
                    );
                    respuesta.put("message", "La entrada ya tiene una transferencia pendiente");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (contarTransferenciasAceptadas(connection, idEntrada) >= MAXIMO_TRANSFERENCIAS_ACEPTADAS) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "LIMITE_TRANSFERENCIAS_ALCANZADO"
                    );
                    respuesta.put("message", "La entrada ya alcanzo el maximo de 3 transferencias aceptadas");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                int nroMovimiento = calcularSiguienteNroMovimiento(connection, idEntrada);

                insertarTransferenciaPendiente(
                        connection,
                        idEntrada,
                        nroMovimiento,
                        idActor,
                        destinatario.idUsuario()
                );

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "TRANSFERENCIA_SOLICITADA");
                respuesta.put("idEntrada", idEntrada);
                respuesta.put("nroMovimiento", nroMovimiento);
                respuesta.put("estadoMovimiento", "PENDIENTE");
                respuesta.put("correoDestinatario", destinatario.correo());

                return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_TRANSFERENCIAS");
                respuesta.put("message", "No se pudo registrar la solicitud de transferencia");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "ERROR_DB_TRANSFERENCIAS");
            respuesta.put("message", "No se pudo registrar la solicitud de transferencia");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    @PostMapping("/entradas/{idEntrada}/transferencias/{nroMovimiento}/aceptar")
    public ResponseEntity<Map<String, Object>> aceptarTransferencia(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada,
            @PathVariable("nroMovimiento") int nroMovimiento) {

        return responderTransferencia(authorizationHeader, idEntrada, nroMovimiento, true);
    }

    @PostMapping("/entradas/{idEntrada}/transferencias/{nroMovimiento}/rechazar")
    public ResponseEntity<Map<String, Object>> rechazarTransferencia(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada,
            @PathVariable("nroMovimiento") int nroMovimiento) {

        return responderTransferencia(authorizationHeader, idEntrada, nroMovimiento, false);
    }

    @PostMapping("/entradas/{idEntrada}/transferencias/{nroMovimiento}/cancelar")
    public ResponseEntity<Map<String, Object>> cancelarTransferencia(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("idEntrada") int idEntrada,
            @PathVariable("nroMovimiento") int nroMovimiento) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        if (idEntrada <= 0 || nroMovimiento <= 0) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", "idEntrada y nroMovimiento deben ser mayores a cero");

            return ResponseEntity.badRequest().body(respuesta);
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idActor = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idActor == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                EntradaBloqueada entrada = bloquearEntrada(connection, idEntrada);

                if (entrada == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_ENCONTRADA");
                    respuesta.put("message", "La entrada solicitada no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                TransferenciaBloqueada transferencia = buscarTransferenciaBloqueada(
                        connection,
                        idEntrada,
                        nroMovimiento
                );

                if (transferencia == null || !"TRANSFERENCIA".equals(transferencia.tipoMovimiento())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TRANSFERENCIA_NO_ENCONTRADA");
                    respuesta.put("message", "La transferencia solicitada no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (transferencia.idTitularOrigen() != idActor) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_ES_EMISOR");
                    respuesta.put("message", "El usuario autenticado no es el emisor de la transferencia");

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                if (!"PENDIENTE".equals(transferencia.estadoMovimiento())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TRANSFERENCIA_NO_PENDIENTE");
                    respuesta.put("message", "La transferencia ya no esta pendiente");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (!esTransferenciaPendienteCoherente(transferencia)) {
                    connection.rollback();
                    return respuestaTransferenciaInconsistente();
                }

                Timestamp instanteCancelacion = obtenerAhoraBase(connection);
                int filasActualizadas = marcarTransferenciaCancelada(
                        connection,
                        idEntrada,
                        nroMovimiento,
                        instanteCancelacion
                );

                if (filasActualizadas != 1) {
                    connection.rollback();
                    return respuestaTransferenciaInconsistente();
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "TRANSFERENCIA_CANCELADA");
                respuesta.put("idEntrada", idEntrada);
                respuesta.put("nroMovimiento", nroMovimiento);
                respuesta.put("estadoMovimiento", "CANCELADA");
                respuesta.put("fechaRespuesta", timestampAString(instanteCancelacion));

                return ResponseEntity.ok(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase(
                        "ERROR",
                        "ERROR_DB_CANCELAR_TRANSFERENCIA"
                );
                respuesta.put("message", "No se pudo cancelar la transferencia");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase(
                    "ERROR",
                    "ERROR_DB_CANCELAR_TRANSFERENCIA"
            );
            respuesta.put("message", "No se pudo cancelar la transferencia");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static ResponseEntity<Map<String, Object>> responderTransferencia(
            String authorizationHeader,
            int idEntrada,
            int nroMovimiento,
            boolean aceptar
    ) {

        VerifiedFirebaseToken token;

        try {
            token = FirebaseTokenVerifier.verifyAuthorizationHeader(authorizationHeader);
        } catch (InvalidAuthorizationException e) {
            return respuestaNoAutorizada(e.getCode(), e.getMessage());
        }

        if (idEntrada <= 0 || nroMovimiento <= 0) {
            Map<String, Object> respuesta = respuestaBase("ERROR", "INPUT_INVALIDO");
            respuesta.put("message", "idEntrada y nroMovimiento deben ser mayores a cero");

            return ResponseEntity.badRequest().body(respuesta);
        }

        try (Connection connection = DbConnectionFactory.getConnection()) {
            Integer idActor = buscarIdUsuarioPorFirebaseUid(connection, token.uid());

            if (idActor == null) {
                Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_REGISTRADO");
                respuesta.put("message", "El usuario autenticado no existe en USUARIO_GENERAL");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
            }

            connection.setAutoCommit(false);

            try {
                EntradaBloqueada entrada = bloquearEntrada(connection, idEntrada);

                if (entrada == null) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_ENCONTRADA");
                    respuesta.put("message", "La entrada solicitada no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                TransferenciaBloqueada transferencia = buscarTransferenciaBloqueada(
                        connection,
                        idEntrada,
                        nroMovimiento
                );

                if (transferencia == null || !"TRANSFERENCIA".equals(transferencia.tipoMovimiento())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TRANSFERENCIA_NO_ENCONTRADA");
                    respuesta.put("message", "La transferencia solicitada no existe");

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
                }

                if (transferencia.idDestinatario() == null || transferencia.idDestinatario() != idActor) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "USUARIO_NO_ES_DESTINATARIO");
                    respuesta.put("message", "El usuario autenticado no es el destinatario de la transferencia");

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
                }

                if (!"PENDIENTE".equals(transferencia.estadoMovimiento())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "TRANSFERENCIA_NO_PENDIENTE");
                    respuesta.put("message", "La transferencia ya no esta pendiente");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (!esTransferenciaPendienteCoherente(transferencia)) {
                    connection.rollback();
                    return respuestaTransferenciaInconsistente();
                }

                if (!aceptar) {
                    Timestamp instanteRespuesta = obtenerAhoraBase(connection);
                    int filasActualizadas = marcarTransferenciaRechazada(
                            connection,
                            idEntrada,
                            nroMovimiento,
                            instanteRespuesta
                    );

                    if (filasActualizadas != 1) {
                        connection.rollback();
                        return respuestaTransferenciaInconsistente();
                    }

                    connection.commit();

                    Map<String, Object> respuesta = respuestaBase("OK", "TRANSFERENCIA_RECHAZADA");
                    respuesta.put("idEntrada", idEntrada);
                    respuesta.put("nroMovimiento", nroMovimiento);
                    respuesta.put("estadoMovimiento", "RECHAZADA");
                    respuesta.put("fechaRespuesta", timestampAString(instanteRespuesta));

                    return ResponseEntity.ok(respuesta);
                }

                if (!"EMITIDA".equals(entrada.estadoEntrada())) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase("ERROR", "ENTRADA_NO_TRANSFERIBLE");
                    respuesta.put("message", "Solo se puede aceptar la transferencia de una entrada EMITIDA");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                List<MovimientoVigente> movimientosVigentes = buscarMovimientosVigentesBloqueados(
                        connection,
                        idEntrada
                );

                if (movimientosVigentes.size() != 1) {
                    connection.rollback();
                    return respuestaTitularidadInconsistente();
                }

                MovimientoVigente movimientoVigente = movimientosVigentes.get(0);

                if (movimientoVigente.idTitularActual() != transferencia.idTitularOrigen()) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "TITULAR_ORIGEN_YA_NO_VIGENTE"
                    );
                    respuesta.put("message", "El origen de la transferencia ya no es el titular vigente");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                if (contarTransferenciasAceptadas(connection, idEntrada) >= MAXIMO_TRANSFERENCIAS_ACEPTADAS) {
                    connection.rollback();

                    Map<String, Object> respuesta = respuestaBase(
                            "ERROR",
                            "LIMITE_TRANSFERENCIAS_ALCANZADO"
                    );
                    respuesta.put("message", "La entrada ya alcanzo el maximo de 3 transferencias aceptadas");

                    return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
                }

                Timestamp instanteAceptacion = obtenerAhoraBase(connection);

                int movimientosCerrados = cerrarMovimientoVigente(
                        connection,
                        idEntrada,
                        movimientoVigente.nroMovimiento(),
                        instanteAceptacion
                );

                if (movimientosCerrados != 1) {
                    connection.rollback();
                    return respuestaTitularidadInconsistente();
                }

                int transferenciasAceptadas = marcarTransferenciaAceptada(
                        connection,
                        idEntrada,
                        nroMovimiento,
                        instanteAceptacion
                );

                if (transferenciasAceptadas != 1) {
                    connection.rollback();
                    return respuestaTransferenciaInconsistente();
                }

                connection.commit();

                Map<String, Object> respuesta = respuestaBase("OK", "TRANSFERENCIA_ACEPTADA");
                respuesta.put("idEntrada", idEntrada);
                respuesta.put("nroMovimiento", nroMovimiento);
                respuesta.put("estadoMovimiento", "ACEPTADA");
                respuesta.put("fechaDesde", timestampAString(instanteAceptacion));

                return ResponseEntity.ok(respuesta);

            } catch (SQLException e) {
                rollbackSilencioso(connection);

                Map<String, Object> respuesta = respuestaBase(
                        "ERROR",
                        "ERROR_DB_RESPONDER_TRANSFERENCIA"
                );
                respuesta.put("message", "No se pudo responder la transferencia");

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
            }

        } catch (SQLException | IllegalStateException e) {
            Map<String, Object> respuesta = respuestaBase(
                    "ERROR",
                    "ERROR_DB_RESPONDER_TRANSFERENCIA"
            );
            respuesta.put("message", "No se pudo responder la transferencia");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    private static String validarInputTransferencia(
            int idEntrada,
            SolicitarTransferenciaRequest request
    ) {

        if (idEntrada <= 0) {
            return "idEntrada debe ser mayor a cero";
        }

        if (request == null) {
            return "El body de la transferencia es obligatorio";
        }

        if (request.correoDestinatario == null || request.correoDestinatario.trim().isEmpty()) {
            return "correoDestinatario es obligatorio";
        }

        if (request.correoDestinatario.trim().length() > MAXIMO_LARGO_CORREO) {
            return "correoDestinatario no puede superar 120 caracteres";
        }

        return null;
    }

    private static Integer buscarIdUsuarioPorFirebaseUid(Connection connection, String firebaseUid)
            throws SQLException {

        String sql = """
                SELECT id_usuario
                FROM USUARIO_GENERAL
                WHERE firebase_uid = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firebaseUid);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getInt("id_usuario");
            }
        }
    }

    private static ResultadoListadoEntradas buscarEntradasDelTitular(
            Connection connection,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT
                    en.id_entrada,
                    en.estado_entrada,
                    en.fecha_emision,
                    ev.id_evento,
                    ev.fecha_hora_inicio,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    s.id_sector,
                    s.nombre_sector,
                    mae.nro_movimiento,
                    mae.tipo_movimiento,
                    mae.fecha_desde,
                    (
                        SELECT COUNT(*)
                        FROM MOVIMIENTO_ASIGNACION_ENTRADA total
                        WHERE total.id_entrada = en.id_entrada
                          AND total.fecha_desde IS NOT NULL
                          AND total.fecha_hasta IS NULL
                          AND (
                              (total.tipo_movimiento = 'COMPRA_INICIAL'
                               AND total.estado_movimiento = 'CONFIRMADA')
                              OR
                              (total.tipo_movimiento = 'TRANSFERENCIA'
                               AND total.estado_movimiento = 'ACEPTADA')
                          )
                    ) AS cantidad_movimientos_vigentes
                FROM ENTRADA en
                JOIN RESERVA_POR_VENTA rpv
                    ON rpv.id_reserva_por_venta = en.id_reserva_por_venta
                JOIN EVENTO ev
                    ON ev.id_evento = rpv.id_evento
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = ev.id_seleccion_visitante
                JOIN SECTOR s
                    ON s.id_sector = rpv.id_sector
                JOIN MOVIMIENTO_ASIGNACION_ENTRADA mae
                    ON mae.id_entrada = en.id_entrada
                WHERE mae.fecha_desde IS NOT NULL
                  AND mae.fecha_hasta IS NULL
                  AND (
                      (mae.tipo_movimiento = 'COMPRA_INICIAL'
                       AND mae.estado_movimiento = 'CONFIRMADA'
                       AND mae.id_usuario_titular_origen = ?)
                      OR
                      (mae.tipo_movimiento = 'TRANSFERENCIA'
                       AND mae.estado_movimiento = 'ACEPTADA'
                       AND mae.id_usuario_destinatario = ?)
                  )
                ORDER BY ev.fecha_hora_inicio, en.id_entrada
                """;

        List<Map<String, Object>> entradas = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);
            statement.setInt(2, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (resultSet.getInt("cantidad_movimientos_vigentes") != 1) {
                        return new ResultadoListadoEntradas(List.of(), true);
                    }

                    Map<String, Object> entrada = new LinkedHashMap<>();
                    entrada.put("idEntrada", resultSet.getInt("id_entrada"));
                    entrada.put("estadoEntrada", resultSet.getString("estado_entrada"));
                    entrada.put("fechaEmision", timestampAString(resultSet.getTimestamp("fecha_emision")));
                    entrada.put("idEvento", resultSet.getInt("id_evento"));
                    entrada.put("seleccionLocal", resultSet.getString("seleccion_local").trim());
                    entrada.put("seleccionVisitante", resultSet.getString("seleccion_visitante").trim());
                    entrada.put("fechaHoraInicio", timestampAString(resultSet.getTimestamp("fecha_hora_inicio")));
                    entrada.put("idSector", resultSet.getInt("id_sector"));
                    entrada.put("nombreSector", resultSet.getString("nombre_sector").trim());
                    entrada.put("nroMovimiento", resultSet.getInt("nro_movimiento"));
                    entrada.put("tipoMovimiento", resultSet.getString("tipo_movimiento"));
                    entrada.put("fechaDesde", timestampAString(resultSet.getTimestamp("fecha_desde")));

                    entradas.add(entrada);
                }
            }
        }

        return new ResultadoListadoEntradas(entradas, false);
    }

    private static ResultadoTransferenciasRecibidas buscarTransferenciasRecibidas(
            Connection connection,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT
                    mae.id_entrada,
                    mae.nro_movimiento,
                    mae.estado_movimiento,
                    mae.fecha_solicitud,
                    mae.fecha_respuesta,
                    mae.fecha_desde,
                    mae.fecha_hasta,
                    en.estado_entrada,
                    origen.correo AS correo_origen,
                    origen.nombre AS nombre_origen,
                    origen.apellido AS apellido_origen,
                    ev.id_evento,
                    ev.fecha_hora_inicio,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    s.id_sector,
                    s.nombre_sector,
                    (
                        SELECT COUNT(*)
                        FROM MOVIMIENTO_ASIGNACION_ENTRADA pendientes
                        WHERE pendientes.id_entrada = mae.id_entrada
                          AND pendientes.tipo_movimiento = 'TRANSFERENCIA'
                          AND pendientes.estado_movimiento = 'PENDIENTE'
                    ) AS cantidad_pendientes
                FROM MOVIMIENTO_ASIGNACION_ENTRADA mae
                JOIN ENTRADA en
                    ON en.id_entrada = mae.id_entrada
                JOIN RESERVA_POR_VENTA rpv
                    ON rpv.id_reserva_por_venta = en.id_reserva_por_venta
                JOIN EVENTO ev
                    ON ev.id_evento = rpv.id_evento
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = ev.id_seleccion_visitante
                JOIN SECTOR s
                    ON s.id_sector = rpv.id_sector
                JOIN USUARIO_GENERAL origen
                    ON origen.id_usuario = mae.id_usuario_titular_origen
                WHERE mae.id_usuario_destinatario = ?
                  AND mae.tipo_movimiento = 'TRANSFERENCIA'
                  AND mae.estado_movimiento = 'PENDIENTE'
                ORDER BY mae.fecha_solicitud, mae.id_entrada, mae.nro_movimiento
                """;

        List<Map<String, Object>> transferencias = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    boolean fechasIncoherentes =
                            resultSet.getTimestamp("fecha_respuesta") != null
                            || resultSet.getTimestamp("fecha_desde") != null
                            || resultSet.getTimestamp("fecha_hasta") != null;

                    if (resultSet.getInt("cantidad_pendientes") != 1 || fechasIncoherentes) {
                        return new ResultadoTransferenciasRecibidas(List.of(), true);
                    }

                    Map<String, Object> transferencia = new LinkedHashMap<>();
                    transferencia.put("idEntrada", resultSet.getInt("id_entrada"));
                    transferencia.put("nroMovimiento", resultSet.getInt("nro_movimiento"));
                    transferencia.put("estadoMovimiento", resultSet.getString("estado_movimiento"));
                    transferencia.put(
                            "fechaSolicitud",
                            timestampAString(resultSet.getTimestamp("fecha_solicitud"))
                    );
                    transferencia.put("estadoEntrada", resultSet.getString("estado_entrada"));
                    transferencia.put("correoOrigen", resultSet.getString("correo_origen").trim());
                    transferencia.put("nombreOrigen", resultSet.getString("nombre_origen").trim());
                    transferencia.put("apellidoOrigen", resultSet.getString("apellido_origen").trim());
                    transferencia.put("idEvento", resultSet.getInt("id_evento"));
                    transferencia.put("seleccionLocal", resultSet.getString("seleccion_local").trim());
                    transferencia.put(
                            "seleccionVisitante",
                            resultSet.getString("seleccion_visitante").trim()
                    );
                    transferencia.put(
                            "fechaHoraInicio",
                            timestampAString(resultSet.getTimestamp("fecha_hora_inicio"))
                    );
                    transferencia.put("idSector", resultSet.getInt("id_sector"));
                    transferencia.put("nombreSector", resultSet.getString("nombre_sector").trim());

                    transferencias.add(transferencia);
                }
            }
        }

        return new ResultadoTransferenciasRecibidas(transferencias, false);
    }

    private static ResultadoTransferenciasEnviadas buscarTransferenciasEnviadas(
            Connection connection,
            int idUsuario
    ) throws SQLException {

        String sql = """
                SELECT
                    mae.id_entrada,
                    mae.nro_movimiento,
                    mae.estado_movimiento,
                    mae.fecha_solicitud,
                    mae.fecha_respuesta,
                    mae.fecha_desde,
                    mae.fecha_hasta,
                    en.estado_entrada,
                    destino.correo AS correo_destino,
                    destino.nombre AS nombre_destino,
                    destino.apellido AS apellido_destino,
                    ev.id_evento,
                    ev.fecha_hora_inicio,
                    local.nombre AS seleccion_local,
                    visitante.nombre AS seleccion_visitante,
                    s.id_sector,
                    s.nombre_sector,
                    (
                        SELECT COUNT(*)
                        FROM MOVIMIENTO_ASIGNACION_ENTRADA pendientes
                        WHERE pendientes.id_entrada = mae.id_entrada
                          AND pendientes.tipo_movimiento = 'TRANSFERENCIA'
                          AND pendientes.estado_movimiento = 'PENDIENTE'
                    ) AS cantidad_pendientes
                FROM MOVIMIENTO_ASIGNACION_ENTRADA mae
                JOIN ENTRADA en
                    ON en.id_entrada = mae.id_entrada
                JOIN RESERVA_POR_VENTA rpv
                    ON rpv.id_reserva_por_venta = en.id_reserva_por_venta
                JOIN EVENTO ev
                    ON ev.id_evento = rpv.id_evento
                JOIN SELECCION_NACIONAL local
                    ON local.id_seleccion = ev.id_seleccion_local
                JOIN SELECCION_NACIONAL visitante
                    ON visitante.id_seleccion = ev.id_seleccion_visitante
                JOIN SECTOR s
                    ON s.id_sector = rpv.id_sector
                JOIN USUARIO_GENERAL destino
                    ON destino.id_usuario = mae.id_usuario_destinatario
                WHERE mae.id_usuario_titular_origen = ?
                  AND mae.tipo_movimiento = 'TRANSFERENCIA'
                  AND mae.estado_movimiento = 'PENDIENTE'
                ORDER BY mae.fecha_solicitud, mae.id_entrada, mae.nro_movimiento
                """;

        List<Map<String, Object>> transferencias = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idUsuario);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    boolean fechasIncoherentes =
                            resultSet.getTimestamp("fecha_respuesta") != null
                            || resultSet.getTimestamp("fecha_desde") != null
                            || resultSet.getTimestamp("fecha_hasta") != null;

                    if (resultSet.getInt("cantidad_pendientes") != 1 || fechasIncoherentes) {
                        return new ResultadoTransferenciasEnviadas(List.of(), true);
                    }

                    Map<String, Object> transferencia = new LinkedHashMap<>();
                    transferencia.put("idEntrada", resultSet.getInt("id_entrada"));
                    transferencia.put("nroMovimiento", resultSet.getInt("nro_movimiento"));
                    transferencia.put("estadoMovimiento", resultSet.getString("estado_movimiento"));
                    transferencia.put(
                            "fechaSolicitud",
                            timestampAString(resultSet.getTimestamp("fecha_solicitud"))
                    );
                    transferencia.put("estadoEntrada", resultSet.getString("estado_entrada"));
                    transferencia.put("correoDestino", resultSet.getString("correo_destino").trim());
                    transferencia.put("nombreDestino", resultSet.getString("nombre_destino").trim());
                    transferencia.put("apellidoDestino", resultSet.getString("apellido_destino").trim());
                    transferencia.put("idEvento", resultSet.getInt("id_evento"));
                    transferencia.put("seleccionLocal", resultSet.getString("seleccion_local").trim());
                    transferencia.put(
                            "seleccionVisitante",
                            resultSet.getString("seleccion_visitante").trim()
                    );
                    transferencia.put(
                            "fechaHoraInicio",
                            timestampAString(resultSet.getTimestamp("fecha_hora_inicio"))
                    );
                    transferencia.put("idSector", resultSet.getInt("id_sector"));
                    transferencia.put("nombreSector", resultSet.getString("nombre_sector").trim());

                    transferencias.add(transferencia);
                }
            }
        }

        return new ResultadoTransferenciasEnviadas(transferencias, false);
    }

    private static EntradaBloqueada bloquearEntrada(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT id_entrada, estado_entrada
                FROM ENTRADA
                WHERE id_entrada = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new EntradaBloqueada(
                        resultSet.getInt("id_entrada"),
                        resultSet.getString("estado_entrada")
                );
            }
        }
    }

    private static List<MovimientoVigente> buscarMovimientosVigentesBloqueados(
            Connection connection,
            int idEntrada
    ) throws SQLException {

        String sql = """
                SELECT
                    nro_movimiento,
                    tipo_movimiento,
                    id_usuario_titular_origen,
                    id_usuario_destinatario
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND fecha_desde IS NOT NULL
                  AND fecha_hasta IS NULL
                  AND (
                      (tipo_movimiento = 'COMPRA_INICIAL' AND estado_movimiento = 'CONFIRMADA')
                      OR
                      (tipo_movimiento = 'TRANSFERENCIA' AND estado_movimiento = 'ACEPTADA')
                  )
                ORDER BY nro_movimiento
                FOR UPDATE
                """;

        List<MovimientoVigente> movimientos = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tipoMovimiento = resultSet.getString("tipo_movimiento");
                    int idTitularActual;

                    if ("COMPRA_INICIAL".equals(tipoMovimiento)) {
                        idTitularActual = resultSet.getInt("id_usuario_titular_origen");
                    } else {
                        idTitularActual = resultSet.getInt("id_usuario_destinatario");
                    }

                    movimientos.add(new MovimientoVigente(
                            resultSet.getInt("nro_movimiento"),
                            idTitularActual
                    ));
                }
            }
        }

        return movimientos;
    }

    private static TransferenciaBloqueada buscarTransferenciaBloqueada(
            Connection connection,
            int idEntrada,
            int nroMovimiento
    ) throws SQLException {

        String sql = """
                SELECT
                    id_usuario_titular_origen,
                    id_usuario_destinatario,
                    tipo_movimiento,
                    estado_movimiento,
                    fecha_respuesta,
                    fecha_desde,
                    fecha_hasta
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND nro_movimiento = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setInt(2, nroMovimiento);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                int valorDestinatario = resultSet.getInt("id_usuario_destinatario");
                Integer idDestinatario = resultSet.wasNull() ? null : valorDestinatario;

                return new TransferenciaBloqueada(
                        resultSet.getInt("id_usuario_titular_origen"),
                        idDestinatario,
                        resultSet.getString("tipo_movimiento"),
                        resultSet.getString("estado_movimiento"),
                        resultSet.getTimestamp("fecha_respuesta"),
                        resultSet.getTimestamp("fecha_desde"),
                        resultSet.getTimestamp("fecha_hasta")
                );
            }
        }
    }

    private static boolean esTransferenciaPendienteCoherente(TransferenciaBloqueada transferencia) {
        return transferencia.fechaRespuesta() == null
                && transferencia.fechaDesde() == null
                && transferencia.fechaHasta() == null;
    }

    private static Timestamp obtenerAhoraBase(Connection connection) throws SQLException {
        String sql = "SELECT NOW() AS instante";

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery()
        ) {
            resultSet.next();
            return resultSet.getTimestamp("instante");
        }
    }

    private static int cerrarMovimientoVigente(
            Connection connection,
            int idEntrada,
            int nroMovimiento,
            Timestamp instante
    ) throws SQLException {

        String sql = """
                UPDATE MOVIMIENTO_ASIGNACION_ENTRADA
                SET fecha_hasta = ?
                WHERE id_entrada = ?
                  AND nro_movimiento = ?
                  AND fecha_desde IS NOT NULL
                  AND fecha_hasta IS NULL
                  AND (
                      (tipo_movimiento = 'COMPRA_INICIAL' AND estado_movimiento = 'CONFIRMADA')
                      OR
                      (tipo_movimiento = 'TRANSFERENCIA' AND estado_movimiento = 'ACEPTADA')
                  )
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, instante);
            statement.setInt(2, idEntrada);
            statement.setInt(3, nroMovimiento);
            return statement.executeUpdate();
        }
    }

    private static int marcarTransferenciaAceptada(
            Connection connection,
            int idEntrada,
            int nroMovimiento,
            Timestamp instante
    ) throws SQLException {

        String sql = """
                UPDATE MOVIMIENTO_ASIGNACION_ENTRADA
                SET estado_movimiento = 'ACEPTADA',
                    fecha_respuesta = ?,
                    fecha_desde = ?
                WHERE id_entrada = ?
                  AND nro_movimiento = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'PENDIENTE'
                  AND fecha_respuesta IS NULL
                  AND fecha_desde IS NULL
                  AND fecha_hasta IS NULL
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, instante);
            statement.setTimestamp(2, instante);
            statement.setInt(3, idEntrada);
            statement.setInt(4, nroMovimiento);
            return statement.executeUpdate();
        }
    }

    private static int marcarTransferenciaRechazada(
            Connection connection,
            int idEntrada,
            int nroMovimiento,
            Timestamp instante
    ) throws SQLException {

        String sql = """
                UPDATE MOVIMIENTO_ASIGNACION_ENTRADA
                SET estado_movimiento = 'RECHAZADA',
                    fecha_respuesta = ?
                WHERE id_entrada = ?
                  AND nro_movimiento = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'PENDIENTE'
                  AND fecha_respuesta IS NULL
                  AND fecha_desde IS NULL
                  AND fecha_hasta IS NULL
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, instante);
            statement.setInt(2, idEntrada);
            statement.setInt(3, nroMovimiento);
            return statement.executeUpdate();
        }
    }

    private static int marcarTransferenciaCancelada(
            Connection connection,
            int idEntrada,
            int nroMovimiento,
            Timestamp instante
    ) throws SQLException {

        String sql = """
                UPDATE MOVIMIENTO_ASIGNACION_ENTRADA
                SET estado_movimiento = 'CANCELADA',
                    fecha_respuesta = ?
                WHERE id_entrada = ?
                  AND nro_movimiento = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'PENDIENTE'
                  AND fecha_respuesta IS NULL
                  AND fecha_desde IS NULL
                  AND fecha_hasta IS NULL
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, instante);
            statement.setInt(2, idEntrada);
            statement.setInt(3, nroMovimiento);
            return statement.executeUpdate();
        }
    }

    private static UsuarioDestino buscarUsuarioPorCorreo(Connection connection, String correo) throws SQLException {
        String sql = """
                SELECT id_usuario, correo
                FROM USUARIO_GENERAL
                WHERE correo = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, correo);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new UsuarioDestino(
                        resultSet.getInt("id_usuario"),
                        resultSet.getString("correo").trim()
                );
            }
        }
    }

    private static int contarTransferenciasPendientes(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS cantidad
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'PENDIENTE'
                """;

        return ejecutarConteo(connection, sql, idEntrada);
    }

    private static int contarTransferenciasAceptadas(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS cantidad
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                  AND tipo_movimiento = 'TRANSFERENCIA'
                  AND estado_movimiento = 'ACEPTADA'
                """;

        return ejecutarConteo(connection, sql, idEntrada);
    }

    private static int ejecutarConteo(Connection connection, String sql, int idEntrada) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("cantidad");
            }
        }
    }

    private static int calcularSiguienteNroMovimiento(Connection connection, int idEntrada) throws SQLException {
        String sql = """
                SELECT COALESCE(MAX(nro_movimiento), 0) + 1 AS siguiente_nro
                FROM MOVIMIENTO_ASIGNACION_ENTRADA
                WHERE id_entrada = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("siguiente_nro");
            }
        }
    }

    private static void insertarTransferenciaPendiente(
            Connection connection,
            int idEntrada,
            int nroMovimiento,
            int idTitularOrigen,
            int idDestinatario
    ) throws SQLException {

        String sql = """
                INSERT INTO MOVIMIENTO_ASIGNACION_ENTRADA (
                    id_entrada,
                    nro_movimiento,
                    id_usuario_titular_origen,
                    id_usuario_destinatario,
                    tipo_movimiento,
                    estado_movimiento,
                    fecha_solicitud,
                    fecha_respuesta,
                    fecha_desde,
                    fecha_hasta
                )
                VALUES (?, ?, ?, ?, 'TRANSFERENCIA', 'PENDIENTE', NOW(), NULL, NULL, NULL)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idEntrada);
            statement.setInt(2, nroMovimiento);
            statement.setInt(3, idTitularOrigen);
            statement.setInt(4, idDestinatario);
            statement.executeUpdate();
        }
    }

    private static ResponseEntity<Map<String, Object>> respuestaTransferenciaInconsistente() {
        Map<String, Object> respuesta = respuestaBase("ERROR", "ESTADO_TRANSFERENCIA_INCONSISTENTE");
        respuesta.put("message", "La transferencia no cumple el estado y las fechas esperadas");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
    }

    private static ResponseEntity<Map<String, Object>> respuestaTitularidadInconsistente() {
        Map<String, Object> respuesta = respuestaBase("ERROR", "ESTADO_TITULARIDAD_INCONSISTENTE");
        respuesta.put("message", "No se pudo determinar un unico titular vigente para la entrada");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
    }

    private static ResponseEntity<Map<String, Object>> respuestaNoAutorizada(String code, String mensaje) {
        Map<String, Object> respuesta = respuestaBase("ERROR", code);
        respuesta.put("message", mensaje);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(respuesta);
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

        return timestamp.toLocalDateTime().toString();
    }

    public static class SolicitarTransferenciaRequest {
        public String correoDestinatario;
    }

    private record ResultadoTransferenciasEnviadas(
            List<Map<String, Object>> transferencias,
            boolean transferenciaInconsistente
    ) {
    }

    private record ResultadoTransferenciasRecibidas(
            List<Map<String, Object>> transferencias,
            boolean transferenciaInconsistente
    ) {
    }

    private record TransferenciaBloqueada(
            int idTitularOrigen,
            Integer idDestinatario,
            String tipoMovimiento,
            String estadoMovimiento,
            Timestamp fechaRespuesta,
            Timestamp fechaDesde,
            Timestamp fechaHasta
    ) {
    }

    private record ResultadoListadoEntradas(
            List<Map<String, Object>> entradas,
            boolean titularidadInconsistente
    ) {
    }

    private record EntradaBloqueada(
            int idEntrada,
            String estadoEntrada
    ) {
    }

    private record MovimientoVigente(
            int nroMovimiento,
            int idTitularActual
    ) {
    }

    private record UsuarioDestino(
            int idUsuario,
            String correo
    ) {
    }
}
