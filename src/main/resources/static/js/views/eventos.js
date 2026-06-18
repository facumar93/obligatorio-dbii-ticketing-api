// Vista: navegador de eventos (partidos) disponibles.
import { apiGet, ApiError } from "../api.js";
import { el, mount, sectionHead, vacio, cargando, toastError } from "../ui.js";
import { ticketEvento } from "../components.js";

export async function render(container, { navigate }) {
    const root = el("div", { class: "wrap stack" }, [
        sectionHead("Mundial 2026", "Próximos partidos"),
        cargando(2)
    ]);
    mount(container, root);

    try {
        const eventos = await apiGet("/eventos");
        const cuerpo = !eventos.length
            ? vacio("Todavía no hay eventos cargados.", "Un administrador puede dar de alta partidos.")
            : el("div", { class: "grid auto" }, eventos.map(ev =>
                ticketEvento(ev, [
                    el("button", {
                        class: "btn btn-accent btn-sm", text: "Ver",
                        onclick: () => navigate(`#/eventos/${ev.idEvento}`)
                    })
                ])));
        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Mundial 2026", "Próximos partidos"),
            cuerpo
        ]));
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudieron cargar los eventos.")));
    }
}
