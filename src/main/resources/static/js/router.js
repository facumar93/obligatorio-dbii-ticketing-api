// Hash router del app-shell. Guards por rol. Resiliente a vistas aún no construidas.
import { el, mount, clear } from "./ui.js";
import { getSession } from "./session.js";

// group: 'general' (cualquier registrado) | 'admin' | 'validador'
export const ROUTES = [
    { pattern: "eventos",        label: "Eventos",        group: "general",   nav: true,  module: "./views/eventos.js" },
    { pattern: "eventos/:id",    label: "Evento",         group: "general",   nav: false, module: "./views/evento-detalle.js" },
    { pattern: "comprar",        label: "Comprar",        group: "general",   nav: true,  module: "./views/comprar.js" },
    { pattern: "mis-entradas",   label: "Mis entradas",   group: "general",   nav: true,  module: "./views/mis-entradas.js" },
    { pattern: "mis-compras",    label: "Mis compras",    group: "general",   nav: true,  module: "./views/mis-compras.js" },
    { pattern: "transferencias", label: "Transferencias", group: "general",   nav: true,  module: "./views/transferencias.js" },
    { pattern: "reportes",       label: "Reportes",       group: "admin",     nav: true,  module: "./views/reportes.js" },
    { pattern: "admin/evento",   label: "Alta evento",    group: "admin",     nav: true,  module: "./views/admin-evento.js" },
    { pattern: "admin/dispositivos", label: "Dispositivos", group: "admin",   nav: true,  module: "./views/admin-dispositivos.js" },
    { pattern: "validacion",     label: "Validación",     group: "validador", nav: true,  module: "./views/validacion.js" }
];

const DEFAULT_HASH = "#/eventos";
let container = null;

function permitido(route, session) {
    if (route.group === "admin") return !!session.esAdmin;
    if (route.group === "validador") return !!session.esValidador;
    return true; // general
}

function match(path) {
    const segs = path.split("/").filter(Boolean);
    for (const route of ROUTES) {
        const pat = route.pattern.split("/");
        if (pat.length !== segs.length) continue;
        const params = {};
        let ok = true;
        for (let i = 0; i < pat.length; i++) {
            if (pat[i].startsWith(":")) params[pat[i].slice(1)] = decodeURIComponent(segs[i]);
            else if (pat[i] !== segs[i]) { ok = false; break; }
        }
        if (ok) return { route, params };
    }
    return null;
}

function vista403() {
    return el("div", { class: "empty" }, [
        el("div", { class: "big", text: "403" }),
        el("p", { text: "No tenés permisos para esta sección." })
    ]);
}
function vistaNoDisponible(nombre) {
    return el("div", { class: "empty" }, [
        el("div", { class: "big", text: "Próximamente" }),
        el("p", { text: `La vista "${nombre}" todavía no está disponible.` })
    ]);
}

async function navigate() {
    if (!container) return;
    const session = getSession();
    if (!session || !session.registered) return;

    const path = (location.hash || DEFAULT_HASH).replace(/^#\/?/, "");
    const found = match(path);

    document.querySelectorAll(".navlink").forEach(n =>
        n.classList.toggle("active", n.dataset.hash === "#/" + (found ? found.route.pattern : "")));

    if (!found) { mount(container, vistaNoDisponible(path || "inicio")); return; }
    if (!permitido(found.route, session)) { mount(container, vista403()); return; }

    mount(container, el("div", { class: "wrap" }, el("div", {}, "Cargando…")));
    try {
        const mod = await import(found.route.module);
        clear(container);
        await mod.render(container, { session, params: found.params, navigate: go });
    } catch (e) {
        console.error("Error cargando vista", found.route.module, e);
        mount(container, vistaNoDisponible(found.route.label));
    }
}

export function go(hash) {
    if (location.hash === hash) navigate();
    else location.hash = hash;
}

export function startRouter(viewContainer) {
    container = viewContainer;
    window.addEventListener("hashchange", navigate);
    if (!location.hash) location.hash = DEFAULT_HASH;
    else navigate();
}
