// Cliente HTTP del frontend. Inyecta el Bearer token, parsea la respuesta y
// normaliza los errores del envelope {status,code,message} del backend.
import { getIdToken } from "./firebase.js";

export class ApiError extends Error {
    constructor(httpStatus, code, message, body) {
        super(message || code || `HTTP ${httpStatus}`);
        this.name = "ApiError";
        this.httpStatus = httpStatus;
        this.code = code || null;
        this.body = body;
    }
}

async function parse(respuesta) {
    const texto = await respuesta.text();
    if (!texto) return null;
    try { return JSON.parse(texto); } catch { return texto; }
}

async function request(method, path, { auth = true, body = undefined } = {}) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (auth) headers["Authorization"] = `Bearer ${await getIdToken()}`;

    let respuesta;
    try {
        respuesta = await fetch(path, {
            method,
            headers,
            body: body !== undefined ? JSON.stringify(body) : undefined
        });
    } catch (e) {
        throw new ApiError(0, "ERROR_RED", "No se pudo contactar al servidor", null);
    }

    const datos = await parse(respuesta);

    if (!respuesta.ok) {
        const code = datos && typeof datos === "object" ? datos.code : null;
        const message = datos && typeof datos === "object" ? datos.message : null;
        throw new ApiError(respuesta.status, code, message, datos);
    }

    // Algunos listados devuelven array crudo; otros endpoints, un objeto envelope.
    return datos;
}

export const apiGet = (path) => request("GET", path, { auth: true });
export const apiPost = (path, body) => request("POST", path, { auth: true, body });
export const apiPublicGet = (path) => request("GET", path, { auth: false });
