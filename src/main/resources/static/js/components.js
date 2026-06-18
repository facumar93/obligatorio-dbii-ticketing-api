// Componentes visuales reutilizables: scoreboard y la ticket-stub (signature).
import { el, money, fecha, fechaHora, badge } from "./ui.js";

export function scoreboard(local, visitante) {
    return el("div", { class: "scoreboard" }, [
        el("div", { class: "team local", text: local || "—" }),
        el("div", { class: "vs", text: "VS" }),
        el("div", { class: "team visit", text: visitante || "—" })
    ]);
}

function meta(items) {
    return el("div", { class: "meta" }, items.filter(Boolean).map(([k, v]) =>
        el("span", {}, [document.createTextNode(k + " "), el("b", { text: v })])));
}

// Ticket de evento (browse). actions: array de Nodes para el talón.
export function ticketEvento(ev, actions = []) {
    return el("div", { class: "ticket" }, [
        el("div", { class: "stub-main" }, [
            el("div", { class: "kicker", text: `${ev.paisSede || ""}${ev.ciudad ? " · " + ev.ciudad : ""}` }),
            scoreboard(ev.local, ev.visitante),
            meta([
                ["Estadio", ev.estadio],
                ["Inicio", fechaHora(ev.fechaHoraInicio)],
                ev.capacidadTotal ? ["Cupo total", String(ev.capacidadTotal)] : null
            ])
        ]),
        el("div", { class: "stub-side" }, [
            el("div", { class: "label", text: "Desde" }),
            el("div", { class: "price", text: ev.precioMin != null ? money(ev.precioMin).replace("$ ", "$") : "—" }),
            ...actions
        ])
    ]);
}

// Ticket de entrada (mis-entradas). actions: array de Nodes para el talón.
export function ticketEntrada(ent, actions = []) {
    const consumida = ent.estadoEntrada === "CONSUMIDA" || ent.estadoEntrada === "ANULADA";
    return el("div", { class: "ticket" + (consumida ? " consumed" : "") }, [
        el("div", { class: "stub-main" }, [
            el("div", { class: "row between" }, [
                el("div", { class: "kicker", text: `${ent.estadio || ""}${ent.ciudad ? " · " + ent.ciudad : ""}` }),
                badge(ent.estadoEntrada)
            ]),
            scoreboard(ent.local, ent.visitante),
            meta([
                ["Sector", ent.nombreSector],
                ["Inicio", fechaHora(ent.fechaHoraInicio)],
                ["Transfer.", `${ent.transferenciasAceptadas}/3`]
            ])
        ]),
        el("div", { class: "stub-side" }, [
            el("div", { class: "label", text: "Entrada" }),
            el("div", { class: "serial", text: "#" + String(ent.idEntrada).padStart(5, "0") }),
            ...actions
        ])
    ]);
}
