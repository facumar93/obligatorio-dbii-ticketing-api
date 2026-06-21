// Helpers de UI: construcción de DOM segura (textContent, no innerHTML),
// formato, toasts, modales, badges, tablas y estados vacío/carga.

// el(tag, attrs, children) — crea un elemento. children: string | Node | array.
export function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === "class") node.className = v;
        else if (k === "text") node.textContent = v;
        else if (k === "html") node.innerHTML = v; // usar solo con contenido propio/estático
        else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2), v);
        else if (k === "dataset") Object.assign(node.dataset, v);
        else node.setAttribute(k, v);
    }
    const kids = Array.isArray(children) ? children : [children];
    for (const c of kids) {
        if (c == null || c === false) continue;
        node.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return node;
}

export function clear(node) { node.replaceChildren(); return node; }
export function mount(node, ...children) { clear(node); node.append(...children.flat(Infinity).filter(Boolean)); return node; }

// --- Formato ---
const fmtMoney = new Intl.NumberFormat("es-UY", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export function money(n) {
    const v = Number(n);
    return Number.isFinite(v) ? `$ ${fmtMoney.format(v)}` : "—";
}
export function fechaHora(iso) {
    if (!iso) return "—";
    const d = new Date(iso.length <= 10 ? iso + "T00:00:00" : iso.replace(" ", "T"));
    if (isNaN(d)) return iso;
    return d.toLocaleString("es-UY", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}
export function fecha(iso) {
    if (!iso) return "—";
    const d = new Date(iso.length <= 10 ? iso + "T00:00:00" : iso.replace(" ", "T"));
    if (isNaN(d)) return iso;
    return d.toLocaleDateString("es-UY", { day: "2-digit", month: "short", year: "numeric" });
}

// --- Badge según estado ---
const ESTADO_CLASE = {
    PAGA: "ok", CONFIRMADA: "ok", ACEPTADA: "ok", EMITIDA: "ok", ACTIVA: "ok", ACTIVO: "ok", APROBADO: "ok", VERIFICADO: "ok",
    PENDIENTE: "warn", RESERVADA: "warn",
    CANCELADA: "bad", RECHAZADA: "bad", ANULADA: "bad", EXPIRADA: "bad", BAJA: "bad", INACTIVO: "bad", RECHAZADO: "bad",
    CONSUMIDA: "info", FINALIZADA: "info", SUSPENDIDA: "warn"
};
export function badge(estado) {
    const clase = ESTADO_CLASE[String(estado || "").toUpperCase()] || "";
    return el("span", { class: `badge ${clase}`, text: estado || "—" });
}

// --- Tabla genérica ---
// headers: [{key, label, num?, render?(row)}]; rows: array de objetos
export function tabla(headers, rows) {
    const thead = el("thead", {}, el("tr", {}, headers.map(h =>
        el("th", { class: h.num ? "num" : "", text: h.label }))));
    const tbody = el("tbody", {}, rows.map(r =>
        el("tr", {}, headers.map(h => {
            const td = el("td", { class: h.num ? "num" : "" });
            const content = h.render ? h.render(r) : r[h.key];
            td.append(content && content.nodeType ? content : document.createTextNode(content == null ? "—" : String(content)));
            return td;
        }))));
    return el("table", {}, [thead, tbody]);
}

// --- Estados ---
export function vacio(mensaje, sub) {
    return el("div", { class: "empty" }, [
        el("div", { class: "big", text: "Sin datos" }),
        el("p", { text: mensaje }),
        sub ? el("p", { class: "tiny", text: sub }) : null
    ]);
}
export function cargando(n = 3) {
    return el("div", { class: "stack" }, Array.from({ length: n }, () => el("div", { class: "skeleton" })));
}

// --- Toast ---
function toastHost() {
    let host = document.querySelector(".toast-host");
    if (!host) { host = el("div", { class: "toast-host" }); document.body.append(host); }
    return host;
}
export function toast(mensaje, tipo = "info", titulo) {
    const t = el("div", { class: `toast ${tipo}` }, [
        titulo ? el("div", { class: "t-title", text: titulo }) : null,
        el("div", { text: mensaje })
    ]);
    toastHost().append(t);
    setTimeout(() => t.remove(), 4200);
}
export function toastError(e) {
    const msg = e && e.message ? e.message : "Ocurrió un error";
    const code = e && e.code ? e.code : null;
    toast(msg, "error", code || "ERROR");
}

// --- Modal ---
export function modal(contenido, { onClose } = {}) {
    const backdrop = el("div", { class: "modal-backdrop" });
    const cerrar = () => { backdrop.remove(); document.removeEventListener("keydown", onKey); onClose && onClose(); };
    const onKey = (e) => { if (e.key === "Escape") cerrar(); };
    const box = el("div", { class: "modal" }, [
        el("button", { class: "modal-close", text: "×", onclick: cerrar, "aria-label": "Cerrar" }),
        contenido
    ]);
    backdrop.append(box);
    backdrop.addEventListener("click", (e) => { if (e.target === backdrop) cerrar(); });
    document.addEventListener("keydown", onKey);
    document.body.append(backdrop);
    return { close: cerrar, box };
}

// --- Helpers de sección ---
export function sectionHead(kicker, titulo, acciones) {
    return el("div", { class: "section-head" }, [
        el("div", {}, [
            kicker ? el("div", { class: "kicker", text: kicker }) : null,
            el("div", { class: "title", text: titulo })
        ]),
        acciones ? el("div", { class: "row" }, acciones) : null
    ]);
}
