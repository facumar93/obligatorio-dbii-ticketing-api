// Vista: historial de compras (ventas) del usuario, con sus líneas.
import { apiGet } from "../api.js";
import { el, mount, money, fechaHora, badge, cargando, vacio, toastError, sectionHead } from "../ui.js";
import { scoreboard } from "../components.js";

export async function render(container) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Historial", "Mis compras"), cargando(2)]));

    let ventas;
    try {
        ventas = await apiGet("/mis-compras");
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudieron cargar tus compras.")));
        return;
    }

    if (!ventas.length) {
        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Historial", "Mis compras"),
            vacio("Todavía no hiciste compras.")
        ]));
        return;
    }

    const cards = ventas.map(v => el("div", { class: "card stack" }, [
        el("div", { class: "row between" }, [
            el("div", {}, [
                el("div", { class: "kicker", text: `Venta #${String(v.idVenta).padStart(5, "0")} · ${fechaHora(v.fechaVenta)}` }),
                el("div", { class: "h3", text: `${money(v.montoTotal)} total` })
            ]),
            badge(v.estadoVenta)
        ]),
        el("div", { class: "stack" }, (v.lineas || []).map(l => el("div", { class: "cart-line" }, [
            el("div", {}, [
                scoreboard(l.local, l.visitante),
                el("div", { class: "tiny muted", text: `${l.nombreSector} · ${fechaHora(l.fechaHoraInicio)}` })
            ]),
            el("div", { class: "chip num", text: `x${l.cantidad}` }),
            el("div", { class: "price-tag num", text: money(l.subtotal) }),
            el("div", {}, badge(l.estadoReserva))
        ]))),
        el("div", { class: "hairline" }),
        el("div", { class: "row between small muted" }, [
            el("span", { text: `Base ${money(v.montoBase)} + comisión ${v.porcentajeComision}%` }),
            el("span", { class: "price-tag num", text: money(v.montoTotal) })
        ])
    ]));

    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Historial", "Mis compras"), ...cards]));
}
