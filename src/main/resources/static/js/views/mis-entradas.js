// Vista: entradas de las que el usuario es titular vigente.
import { apiGet } from "../api.js";
import { el, mount, modal, tabla, badge, fechaHora, cargando, vacio, toast, toastError, sectionHead } from "../ui.js";
import { ticketEntrada } from "../components.js";

export async function render(container, { navigate }) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Mi billetera", "Mis entradas"), cargando(2)]));

    let entradas;
    try {
        entradas = await apiGet("/mis-entradas");
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudieron cargar tus entradas.")));
        return;
    }

    if (!entradas.length) {
        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Mi billetera", "Mis entradas"),
            vacio("Todavía no tenés entradas.", "Comprá entradas para tus partidos.")
        ]));
        return;
    }

    const grid = el("div", { class: "grid auto" }, entradas.map(ent => {
        const actions = [];
        if (ent.estadoEntrada === "EMITIDA") {
            actions.push(el("button", { class: "btn btn-accent btn-sm", text: "QR", onclick: () => verQr(ent) }));
        }
        if (ent.transferible) {
            actions.push(el("button", { class: "btn btn-ghost btn-sm", text: "Transferir", onclick: () => navigate("#/transferencias") }));
        }
        actions.push(el("button", { class: "btn btn-ghost btn-sm", text: "Historial", onclick: () => verHistorial(ent.idEntrada) }));
        return ticketEntrada(ent, actions);
    }));

    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Mi billetera", "Mis entradas"), grid]));
}

async function verHistorial(idEntrada) {
    try {
        const chain = await apiGet(`/entradas/${idEntrada}/historial`);
        const t = tabla([
            { key: "nroMovimiento", label: "#", num: true },
            { key: "tipo", label: "Tipo" },
            { key: "estado", label: "Estado", render: (r) => badge(r.estado) },
            { key: "origen", label: "De" },
            { key: "destinatario", label: "A" },
            { key: "fechaDesde", label: "Desde", render: (r) => fechaHora(r.fechaDesde) }
        ], chain);
        modal(el("div", { class: "stack" }, [
            el("div", { class: "kicker", text: `Entrada #${String(idEntrada).padStart(5, "0")}` }),
            el("div", { class: "h2", text: "Cadena de custodia" }),
            t
        ]));
    } catch (e) {
        toastError(e);
    }
}

async function verQr(ent) {
    try {
        const mod = await import("../qr.js");
        mod.mostrarQr(ent);
    } catch (e) {
        toast("El módulo de QR todavía no está disponible.", "info", "QR");
    }
}
