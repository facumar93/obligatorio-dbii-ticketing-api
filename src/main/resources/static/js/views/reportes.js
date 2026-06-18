// Vista admin: reportes estadísticos.
import { apiGet } from "../api.js";
import { el, mount, tabla, money, badge, cargando, vacio, toastError, sectionHead } from "../ui.js";

export async function render(container) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Estadísticas", "Reportes"), cargando(3)]));

    let ventas, compradores, ocupacion, transfer, validaciones;
    try {
        [ventas, compradores, ocupacion, transfer, validaciones] = await Promise.all([
            apiGet("/reportes/ventas-por-evento"),
            apiGet("/reportes/mayores-compradores"),
            apiGet("/reportes/ocupacion"),
            apiGet("/reportes/transferencias"),
            apiGet("/reportes/validaciones")
        ]);
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudieron cargar los reportes (requiere rol admin).")));
        return;
    }

    const totalEntradas = ventas.reduce((a, r) => a + Number(r.entradasVendidas || 0), 0);
    const totalRecaudado = compradores.reduce((a, r) => a + Number(r.totalInvertido || 0), 0);
    const totalTransfer = transfer.reduce((a, r) => a + Number(r.cantidad || 0), 0);

    const tiles = el("div", { class: "grid cols-3" }, [
        stat(totalEntradas, "Entradas vendidas", "volt"),
        stat(money(totalRecaudado), "Recaudado (PAGA)", "celeste"),
        stat(totalTransfer, "Transferencias", "")
    ]);

    const secVentas = bloque("Eventos más vendidos", ventas.length ? tabla([
        { key: "partido", label: "Partido", render: r => `${r.local} vs ${r.visitante}` },
        { key: "estadio", label: "Estadio" },
        { key: "entradasVendidas", label: "Entradas", num: true }
    ], ventas) : vacio("Sin ventas aún."));

    const secCompradores = bloque("Ranking de mayores compradores", compradores.length ? tabla([
        { key: "comprador", label: "Comprador", render: r => `${r.nombre} ${r.apellido}` },
        { key: "correo", label: "Correo" },
        { key: "ventas", label: "Compras", num: true },
        { key: "totalInvertido", label: "Invertido", num: true, render: r => money(r.totalInvertido) }
    ], compradores) : vacio("Sin compradores aún."));

    const secOcupacion = bloque("Ocupación por evento/sector", ocupacion.length ? tabla([
        { key: "partido", label: "Partido", render: r => `${r.local} vs ${r.visitante}` },
        { key: "nombreSector", label: "Sector" },
        { key: "vendidas", label: "Vendidas", num: true },
        { key: "capacidadHabilitada", label: "Capacidad", num: true },
        { key: "pctOcupacion", label: "% ocup.", num: true, render: r => `${r.pctOcupacion ?? 0}%` }
    ], ocupacion) : vacio("Sin datos."));

    const secTransfer = bloque("Transferencias por estado",
        transfer.length ? el("div", { class: "grid cols-3" }, transfer.map(t =>
            el("div", { class: "row between card" }, [badge(t.estadoMovimiento), el("span", { class: "num h3", text: t.cantidad })])))
            : vacio("Sin transferencias."));

    const secValid = bloque("Validaciones de ingreso", validaciones.length ? tabla([
        { key: "resultadoValidacion", label: "Resultado", render: r => badge(r.resultadoValidacion) },
        { key: "motivoRechazo", label: "Motivo", render: r => r.motivoRechazo || "—" },
        { key: "cantidad", label: "Cantidad", num: true }
    ], validaciones) : vacio("Sin validaciones registradas."));

    mount(container, el("div", { class: "wrap stack" }, [
        sectionHead("Estadísticas", "Reportes"),
        tiles, secVentas, secCompradores, secOcupacion, secTransfer, secValid
    ]));
}

function stat(valor, label, clase) {
    return el("div", { class: "stat " + clase }, [el("div", { class: "v num", text: String(valor) }), el("div", { class: "l", text: label })]);
}
function bloque(titulo, contenido) {
    return el("div", { class: "stack" }, [el("div", { class: "kicker", text: titulo }), el("div", { class: "card" }, contenido)]);
}
