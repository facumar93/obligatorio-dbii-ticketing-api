-- Implementa la especializacion parcial y exclusiva (p,e) del MER:
-- un usuario general no puede ser administrador y validador a la vez.
-- Requiere el privilegio TRIGGER, actualmente no disponible para el usuario
-- de la catedra. El script queda listo hasta obtener dicho permiso.
-- Mientras tanto, la exclusividad se garantiza mediante provision interna
-- controlada de los roles y seeds verificados.

DELIMITER $$

CREATE TRIGGER trg_admin_rol_exclusivo
BEFORE INSERT ON USUARIO_ADMINISTRADOR
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1
        FROM USUARIO_DE_VALIDACION
        WHERE id_usuario = NEW.id_usuario
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'El usuario ya es validador; los roles son mutuamente excluyentes';
    END IF;
END$$

CREATE TRIGGER trg_validador_rol_exclusivo
BEFORE INSERT ON USUARIO_DE_VALIDACION
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1
        FROM USUARIO_ADMINISTRADOR
        WHERE id_usuario = NEW.id_usuario
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'El usuario ya es administrador; los roles son mutuamente excluyentes';
    END IF;
END$$

DELIMITER ;
