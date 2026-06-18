// Vista validador: escanear/validar ingreso a un evento/sector asignado.
import { apiGet, apiPost } from "../api.js";
import { el, mount, badge, fechaHora, cargando, vacio, toast, toastError, sectionHead } from "../ui.js";

export async function render(container) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Control de acceso", "Validación de ingreso"), cargando(1)]));

    let asignaciones;
    try {
        asignaciones = await apiGet("/validacion/mis-asignaciones");
    } catch (e) {
        toastError(e);
        mount(container, el("div", { class: "wrap" }, vacio("No se pudieron cargar tus asignaciones (requiere rol validador).")));
        return;
    }

    if (!asignaciones.length) {
        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Control de acceso", "Validación de ingreso"),
            vacio("No tenés puestos de validación asignados.", "Un administrador debe vincular tu dispositivo y asignarlo a un evento/sector.")
        ]));
        return;
    }

    const selAsig = el("select", {}, asignaciones.map(a =>
        el("option", { value: a.idAsignacionDispositivoValidador, text: `${a.local} vs ${a.visitante} · ${a.estadio} · sector ${a.nombreSector}` })));
    const codigo = el("input", { type: "text", placeholder: "Pegá el código del QR (ej 12.58423.ab12…)", autocomplete: "off" });
    const feed = el("div", { class: "stack" });

    async function validar() {
        const cod = codigo.value.trim();
        if (!cod) { toast("Ingresá o escaneá un código.", "error"); return; }
        try {
            const r = await apiPost("/validacion/validar", {
                idAsignacionDispositivoValidador: Number(selAsig.value),
                codigoQrLeido: cod
            });
            feed.prepend(filaResultado(r));
            codigo.value = "";
            codigo.focus();
        } catch (e) {
            toastError(e);
        }
    }

    function filaResultado(r) {
        const ok = r.resultado === "ACEPTADA";
        return el("div", { class: "card row between" }, [
            el("div", {}, [
                el("b", { text: `Entrada #${String(r.idEntrada).padStart(5, "0")}` }),
                el("div", { class: "tiny muted", text: ok ? "Ingreso aceptado · consumida" : `Rechazada · ${r.motivo}` })
            ]),
            badge(r.resultado),
            el("span", { class: "tiny muted", text: fechaHora(new Date().toISOString()) })
        ]);
    }

    codigo.addEventListener("keydown", (e) => { if (e.key === "Enter") validar(); });

    mount(container, el("div", { class: "wrap stack" }, [
        sectionHead("Control de acceso", "Validación de ingreso"),
        el("div", { class: "card stack" }, [
            el("div", { class: "field" }, [el("label", { text: "Puesto de validación" }), selAsig]),
            el("div", { class: "field" }, [el("label", { text: "Código QR leído" }), codigo]),
            el("button", { class: "btn btn-primary", text: "Validar ingreso", onclick: validar }),
            el("p", { class: "tiny muted", text: "Tip de demo: abrí el QR de una entrada en otra pestaña y pegá su código de texto acá." })
        ]),
        el("div", { class: "kicker", text: "Lecturas recientes" }),
        feed
    ]));
}
