// Identidad de dominio + roles, resueltos una vez tras el login y cacheados.
// Firebase autentica; el backend resuelve id_usuario y roles; acá se memorizan.
import { apiPost, apiGet, ApiError } from "./api.js";

let sesion = null;

export function getSession() { return sesion; }
export function isAdmin() { return !!(sesion && sesion.esAdmin); }
export function isValidador() { return !!(sesion && sesion.esValidador); }
export function clearSession() { sesion = null; }

// Reconcilia identidad (vincula firebase_uid a un usuario por correo si corresponde)
// y obtiene los roles. Devuelve { registered, ...roles } o lanza en error real de red/DB.
export async function refreshSession() {
    // 1) resolve: vincula/normaliza la identidad. No frena el flujo si devuelve REGISTRO_REQUERIDO.
    try {
        await apiPost("/auth/resolve");
    } catch (e) {
        if (!(e instanceof ApiError)) throw e;
        // REGISTRO_REQUERIDO / EMAIL_NO_VERIFICADO se reflejan luego en /me/roles.
    }

    // 2) roles: identidad de dominio consolidada.
    try {
        const roles = await apiGet("/me/roles");
        sesion = {
            registered: true,
            idUsuario: roles.idUsuario,
            email: roles.email,
            nombre: roles.nombre,
            estadoVerificacion: roles.estadoVerificacion,
            esAdmin: !!roles.esAdmin,
            esValidador: !!roles.esValidador,
            adminPaisesSede: roles.adminPaisesSede || []
        };
        return sesion;
    } catch (e) {
        if (e instanceof ApiError && e.httpStatus === 403 && e.code === "USUARIO_NO_REGISTRADO") {
            sesion = { registered: false };
            return sesion;
        }
        throw e;
    }
}
