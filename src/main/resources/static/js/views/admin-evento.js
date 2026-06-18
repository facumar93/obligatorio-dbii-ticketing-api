// Vista admin: alta de evento (EVENTO + EVENTO_SECTOR) en la jurisdicción del admin.
import { apiGet, apiPost } from "../api.js";
import { el, mount, cargando, vacio, toast, toastError, sectionHead } from "../ui.js";

export async function render(container) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Administración", "Alta de evento"), cargando(1)]));

    let estadios, selecciones;
    try {
        [estadios, selecciones] = await Promise.all([apiGet("/admin/estadios"), apiGet("/admin/selecciones")]);
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudo cargar el panel admin.")));
        return;
    }
    if (!estadios.length) {
        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Administración", "Alta de evento"),
            vacio("No tenés estadios en tu jurisdicción.", "Tu asignación administrativa vigente determina los estadios disponibles.")
        ]));
        return;
    }

    const selEstadio = el("select", {}, [el("option", { value: "", text: "Elegí un estadio…" }),
        ...estadios.map(e => el("option", { value: e.idEstadio, text: `${e.nombre} · ${e.ciudad} (${e.pais})` }))]);
    const selLocal = el("select", {}, [el("option", { value: "", text: "Local…" }), ...selecciones.map(s => el("option", { value: s.idSeleccion, text: s.nombre }))]);
    const selVisit = el("select", {}, [el("option", { value: "", text: "Visitante…" }), ...selecciones.map(s => el("option", { value: s.idSeleccion, text: s.nombre }))]);
    const inicio = el("input", { type: "datetime-local" });
    const fin = el("input", { type: "datetime-local" });
    const sectoresHost = el("div", { class: "stack" }, el("p", { class: "muted", text: "Elegí un estadio para ver sus sectores." }));
    let sectoresEstadio = [];

    selEstadio.addEventListener("change", async () => {
        if (!selEstadio.value) { sectoresEstadio = []; mount(sectoresHost, el("p", { class: "muted", text: "Elegí un estadio." })); return; }
        mount(sectoresHost, cargando(1));
        try {
            sectoresEstadio = await apiGet(`/admin/estadios/${selEstadio.value}/sectores`);
            mount(sectoresHost, sectoresEstadio.length ? el("div", { class: "stack" }, sectoresEstadio.map(filaSector))
                : el("p", { class: "muted", text: "El estadio no tiene sectores cargados." }));
        } catch (e) { toastError(e); }
    });

    function filaSector(s) {
        const chk = el("input", { type: "checkbox", dataset: { id: s.idSector } });
        const precio = el("input", { type: "number", min: "1", step: "0.01", placeholder: "precio", style: "max-width:120px", dataset: { precio: s.idSector } });
        const cap = el("input", { type: "number", min: "1", max: String(s.capacidadMax), placeholder: `≤ ${s.capacidadMax}`, style: "max-width:120px", dataset: { cap: s.idSector } });
        return el("div", { class: "cart-line" }, [
            el("label", { class: "row", style: "margin:0" }, [chk, el("b", { text: " " + s.nombreSector })]),
            el("div", { class: "tiny muted", text: `máx ${s.capacidadMax}` }),
            precio, cap
        ]);
    }

    async function confirmar() {
        if (!selEstadio.value || !selLocal.value || !selVisit.value || !inicio.value || !fin.value) {
            toast("Completá estadio, selecciones y fechas.", "error"); return;
        }
        const sectores = [];
        sectoresHost.querySelectorAll('input[type="checkbox"]:checked').forEach(chk => {
            const id = chk.dataset.id;
            const precio = sectoresHost.querySelector(`input[data-precio="${id}"]`).value;
            const cap = sectoresHost.querySelector(`input[data-cap="${id}"]`).value;
            sectores.push({ idSector: Number(id), precioEntrada: Number(precio), capacidadHabilitada: Number(cap) });
        });
        if (!sectores.length) { toast("Habilitá al menos un sector (con precio y capacidad).", "error"); return; }

        try {
            const r = await apiPost("/admin/eventos", {
                idEstadio: Number(selEstadio.value),
                idSeleccionLocal: Number(selLocal.value),
                idSeleccionVisitante: Number(selVisit.value),
                fechaHoraInicio: inicio.value,
                fechaHoraFin: fin.value,
                sectores
            });
            toast(`Evento #${r.idEvento} creado con ${r.sectores} sectores.`, "ok", "Alta OK");
        } catch (e) { toastError(e); }
    }

    mount(container, el("div", { class: "wrap stack" }, [
        sectionHead("Administración", "Alta de evento"),
        el("div", { class: "card stack" }, [
            el("div", { class: "field" }, [el("label", { text: "Estadio" }), selEstadio]),
            el("div", { class: "grid cols-2" }, [
                el("div", { class: "field" }, [el("label", { text: "Selección local" }), selLocal]),
                el("div", { class: "field" }, [el("label", { text: "Selección visitante" }), selVisit])
            ]),
            el("div", { class: "grid cols-2" }, [
                el("div", { class: "field" }, [el("label", { text: "Inicio" }), inicio]),
                el("div", { class: "field" }, [el("label", { text: "Fin" }), fin])
            ]),
            el("div", { class: "kicker", text: "Sectores a habilitar" }),
            sectoresHost,
            el("button", { class: "btn btn-primary", text: "Crear evento", onclick: confirmar })
        ])
    ]));
}
