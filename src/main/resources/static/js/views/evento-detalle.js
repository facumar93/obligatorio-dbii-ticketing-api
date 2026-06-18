// Vista: detalle de un evento con disponibilidad por sector.
import { apiGet } from "../api.js";
import { el, mount, money, fechaHora, badge, cargando, toastError, sectionHead } from "../ui.js";
import { scoreboard } from "../components.js";

export async function render(container, { params, navigate }) {
    mount(container, el("div", { class: "wrap" }, cargando(2)));

    let ev;
    try {
        ev = await apiGet(`/eventos/${params.id}`);
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, el("div", { class: "empty" }, [
            el("div", { class: "big", text: "Evento" }),
            el("p", { text: "No se pudo cargar el evento." }),
            el("button", { class: "btn btn-ghost", text: "Volver", onclick: () => navigate("#/eventos") })
        ])));
        return;
    }

    const filas = (ev.sectores || []).map(s => {
        const agotado = s.disponible <= 0;
        return el("div", { class: "cart-line" }, [
            el("div", {}, [
                el("div", { class: "h3", text: s.nombreSector }),
                el("div", { class: "tiny muted", text: `Capacidad habilitada: ${s.capacidadHabilitada}` })
            ]),
            el("div", { class: "price-tag num", text: money(s.precio) }),
            el("div", {}, agotado ? badge("AGOTADO") : el("span", { class: "chip num", text: `${s.disponible} disp.` })),
            el("button", {
                class: "btn btn-primary btn-sm", text: "Comprar", disabled: agotado,
                onclick: () => {
                    sessionStorage.setItem("preCompra", JSON.stringify({ idEvento: ev.idEvento, idSector: s.idSector }));
                    navigate("#/comprar");
                }
            })
        ]);
    });

    mount(container, el("div", { class: "wrap stack" }, [
        el("button", { class: "btn btn-ghost btn-sm", text: "← Eventos", onclick: () => navigate("#/eventos") }),
        el("div", { class: "card stack" }, [
            el("div", { class: "kicker", text: `${ev.paisSede || ""}${ev.ciudad ? " · " + ev.ciudad : ""}` }),
            scoreboard(ev.local, ev.visitante),
            el("div", { class: "row", text: "" }, [
                el("span", { class: "chip", text: ev.estadio }),
                el("span", { class: "chip", text: fechaHora(ev.fechaHoraInicio) })
            ])
        ]),
        sectionHead("Entradas", "Sectores habilitados"),
        el("div", { class: "card" }, filas.length ? filas : el("p", { class: "muted", text: "Este evento no tiene sectores habilitados." }))
    ]));
}
