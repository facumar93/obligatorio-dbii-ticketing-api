// Vista: compra de entradas (carrito real sobre POST /ventas/comprar).
import { apiGet, apiPost } from "../api.js";
import { el, mount, money, badge, cargando, toast, toastError, sectionHead } from "../ui.js";
import { scoreboard } from "../components.js";

const MAX = 5;

export async function render(container, { navigate }) {
    const estado = { eventos: [], evento: null, carrito: [] }; // carrito: [{idEvento,idSector,nombreSector,precio,cantidad,local,visitante}]

    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Comprar", "Elegí tu partido"), cargando(2)]));

    try {
        estado.eventos = await apiGet("/eventos");
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, el("div", { class: "empty" }, el("p", { text: "No se pudieron cargar los eventos." }))));
        return;
    }

    const sectoresHost = el("div", { class: "card" }, el("p", { class: "muted", text: "Elegí un partido para ver sus sectores." }));
    const carritoHost = el("div", { class: "card stack" });
    const selectEvento = el("select", { onchange: (e) => seleccionarEvento(Number(e.target.value)) }, [
        el("option", { value: "", text: "Seleccionar partido…" }),
        ...estado.eventos.map(ev => el("option", { value: ev.idEvento, text: `${ev.local} vs ${ev.visitante} · ${ev.estadio}` }))
    ]);

    async function seleccionarEvento(idEvento) {
        if (!idEvento) { estado.evento = null; mount(sectoresHost, el("p", { class: "muted", text: "Elegí un partido." })); return; }
        mount(sectoresHost, cargando(1));
        try {
            estado.evento = await apiGet(`/eventos/${idEvento}`);
            selectEvento.value = String(idEvento);
            renderSectores();
        } catch (e) { toastError(e); }
    }

    function renderSectores() {
        const ev = estado.evento;
        const filas = (ev.sectores || []).map(s => {
            const input = el("input", { type: "number", min: "0", max: String(Math.min(MAX, s.disponible)), value: "0", style: "max-width:90px" });
            return el("div", { class: "cart-line" }, [
                el("div", {}, [el("div", { class: "h3", text: s.nombreSector }), el("div", { class: "tiny muted", text: `${s.disponible} disponibles` })]),
                el("div", { class: "price-tag num", text: money(s.precio) }),
                input,
                el("button", {
                    class: "btn btn-primary btn-sm", text: "Agregar", disabled: s.disponible <= 0,
                    onclick: () => agregar(ev, s, Number(input.value))
                })
            ]);
        });
        mount(sectoresHost, el("div", { class: "stack" }, [
            el("div", { class: "kicker", text: `${ev.paisSede || ""} · ${ev.estadio}` }),
            scoreboard(ev.local, ev.visitante),
            el("div", { class: "hairline" }),
            ...(filas.length ? filas : [el("p", { class: "muted", text: "Sin sectores habilitados." })])
        ]));
    }

    function agregar(ev, s, cantidad) {
        if (!cantidad || cantidad < 1) return;
        const totalActual = estado.carrito.reduce((a, l) => a + l.cantidad, 0);
        const existente = estado.carrito.find(l => l.idEvento === ev.idEvento && l.idSector === s.idSector);
        const yaEnLinea = existente ? existente.cantidad : 0;
        if (totalActual - yaEnLinea + cantidad > MAX) {
            toast(`Máximo ${MAX} entradas por compra.`, "error", "Límite");
            return;
        }
        if (existente) existente.cantidad = cantidad;
        else estado.carrito.push({ idEvento: ev.idEvento, idSector: s.idSector, nombreSector: s.nombreSector, precio: Number(s.precio), cantidad, local: ev.local, visitante: ev.visitante });
        renderCarrito();
        toast(`${s.nombreSector} agregado.`, "ok");
    }

    function quitar(idEvento, idSector) {
        estado.carrito = estado.carrito.filter(l => !(l.idEvento === idEvento && l.idSector === idSector));
        renderCarrito();
    }

    function renderCarrito() {
        const total = estado.carrito.reduce((a, l) => a + l.cantidad, 0);
        const base = estado.carrito.reduce((a, l) => a + l.precio * l.cantidad, 0);
        if (!estado.carrito.length) {
            mount(carritoHost, [el("div", { class: "kicker", text: "Carrito" }), el("p", { class: "muted", text: "Vacío." })]);
            return;
        }
        const metodo = el("select", {}, [
            el("option", { value: "TARJETA_CREDITO", text: "Tarjeta de crédito" }),
            el("option", { value: "TARJETA_DEBITO", text: "Tarjeta de débito" }),
            el("option", { value: "TRANSFERENCIA", text: "Transferencia" })
        ]);
        mount(carritoHost, [
            el("div", { class: "row between" }, [el("div", { class: "kicker", text: "Carrito" }), el("span", { class: "chip num", text: `${total}/${MAX}` })]),
            ...estado.carrito.map(l => el("div", { class: "cart-line" }, [
                el("div", {}, [el("b", { text: `${l.local} vs ${l.visitante}` }), el("div", { class: "tiny muted", text: l.nombreSector })]),
                el("div", { class: "chip num", text: `x${l.cantidad}` }),
                el("div", { class: "price-tag num", text: money(l.precio * l.cantidad) }),
                el("button", { class: "btn btn-ghost btn-sm", text: "Quitar", onclick: () => quitar(l.idEvento, l.idSector) })
            ])),
            el("div", { class: "hairline" }),
            el("label", { text: "Método de pago" }), metodo,
            el("div", { class: "row between" }, [
                el("span", { class: "muted small", text: `Subtotal ${money(base)} + comisión al confirmar` }),
                el("button", { class: "btn btn-primary", text: "Confirmar compra", onclick: () => confirmar(metodo.value) })
            ])
        ]);
    }

    async function confirmar(metodoPago) {
        const lineas = estado.carrito.map(l => ({ idEvento: l.idEvento, idSector: l.idSector, cantidad: l.cantidad }));
        if (!lineas.length) { toast("El carrito está vacío.", "error"); return; }
        try {
            const r = await apiPost("/ventas/comprar", { lineas, metodoPago });
            toast(`Compra OK · ${r.cantidadEntradas} entradas · ${money(r.montoTotal)}`, "ok", "¡Listo!");
            estado.carrito = [];
            renderCarrito();
            setTimeout(() => navigate("#/mis-entradas"), 900);
        } catch (e) {
            toastError(e);
        }
    }

    mount(container, el("div", { class: "wrap stack" }, [
        sectionHead("Comprar", "Elegí tu partido"),
        el("div", { class: "grid cols-2" }, [
            el("div", { class: "stack" }, [el("label", { text: "Partido" }), selectEvento, sectoresHost]),
            carritoHost
        ])
    ]));
    renderCarrito();

    // Preselección desde el detalle de evento.
    const pre = sessionStorage.getItem("preCompra");
    if (pre) {
        sessionStorage.removeItem("preCompra");
        try { const { idEvento } = JSON.parse(pre); if (idEvento) await seleccionarEvento(idEvento); } catch { /* ignore */ }
    }
}
