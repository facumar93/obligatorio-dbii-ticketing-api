-- ============================================================
-- Seed mínimo para habilitar el módulo de validación de ingreso.
-- Lo único que NO tiene endpoint (por alcance) es promover un usuario a
-- funcionario de validación; el resto (dispositivo, vinculación, asignación)
-- se hace desde la app con la cuenta admin (panel "Dispositivos").
-- ============================================================

-- 1) Promové un USUARIO_GENERAL ya registrado a funcionario de validación.
--    Usá una cuenta Google distinta de la admin (la exclusividad de rol
--    admin/validador es regla de aplicación, no la fuerza el motor).
--    Reemplazá el correo por el de la cuenta que vas a usar como validador.
INSERT INTO USUARIO_DE_VALIDACION (id_usuario, numero_legajo)
SELECT id_usuario, 'LEG-0001'
FROM USUARIO_GENERAL
WHERE correo = 'CORREO_DEL_VALIDADOR@gmail.com';

-- 2) (Opcional) Verificá que quedó cargado:
-- SELECT uv.id_usuario, uv.numero_legajo, ug.correo
-- FROM USUARIO_DE_VALIDACION uv JOIN USUARIO_GENERAL ug ON ug.id_usuario = uv.id_usuario;

-- 3) El resto se hace desde la app, logueado como ADMIN (cuenta hotmail):
--    Dispositivos -> "Registrar dispositivo"
--                 -> "Vincular" funcionario + dispositivo
--                 -> "Asignar" la vinculación a un Evento + Sector.
--    Luego, como TITULAR de una entrada: abrí su QR y copiá el código de texto;
--    logueado como VALIDADOR, pegalo en "Validación" para validar el ingreso.
