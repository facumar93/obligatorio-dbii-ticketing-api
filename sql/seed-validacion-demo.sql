-- Seed de una sola ejecucion para la demostracion del validador.
-- Anclajes verificados: usuario 2, evento 1, sector 1.
-- No presupone los AUTO_INCREMENT generados.

START TRANSACTION;

INSERT INTO USUARIO_DE_VALIDACION (
    id_usuario,
    numero_legajo
) VALUES (
    2,
    'VAL-0002'
);

INSERT INTO DISPOSITIVO_AUTORIZADO (
    estado
) VALUES (
    'ACTIVO'
);

SET @id_dispositivo = LAST_INSERT_ID();

INSERT INTO VINCULACION_VALIDADOR_DISPOSITIVO (
    id_usuario_validador,
    id_dispositivo,
    fecha_desde,
    fecha_hasta,
    estado_vinculacion
) VALUES (
    2,
    @id_dispositivo,
    CURRENT_TIMESTAMP,
    NULL,
    'ACTIVA'
);

SET @id_vinculacion = LAST_INSERT_ID();

INSERT INTO ASIGNACION_DISPOSITIVO_VALIDADOR (
    id_vinculacion,
    id_evento,
    id_sector,
    fecha_activacion,
    fecha_desactivacion,
    fecha_cancelacion
) VALUES (
    @id_vinculacion,
    1,
    1,
    CURRENT_TIMESTAMP,
    '2026-07-10 20:00:00',
    NULL
);

SET @id_asignacion_dispositivo_validador = LAST_INSERT_ID();

COMMIT;

SELECT
    @id_dispositivo AS id_dispositivo,
    @id_vinculacion AS id_vinculacion,
    @id_asignacion_dispositivo_validador
        AS id_asignacion_dispositivo_validador;
