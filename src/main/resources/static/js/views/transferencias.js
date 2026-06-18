// Vista: transferencias (iniciar / recibidas / enviadas).
import { apiGet, apiPost } from "../api.js";
import { el, mount, tabla, badge, fechaHora, cargando, vacio, toast, toastError, sectionHead } from "../ui.js";

export async function render(container) {
    const tabHost = el("div", { class: "stack" });
    const tabs = el("div", { class: "row" }, [
        tabBtn("Iniciar", () => mostrar("iniciar")),
        tabBtn("Recibidas", () => mostrar("recibidas")),
        tabBtn("Enviadas", () => mostrar("enviadas"))
    ]);

    function tabBtn(label, onclick) {
        return el("button", { class: "btn btn-ghost btn-sm", text: label, dataset: { tab: label }, onclick });
    }
    function marcar(nombre) {
        tabs.querySelectorAll("button").forEach(b =>
            b.classList.toggle("btn-accent", b.dataset.tab.toLowerCase().startsWith(nombre.slice(0, 4))));
    }

    async function mostrar(cual) {
        marcar(cual);
        mount(tabHost, cargando(1));
        try {
            if (cual === "iniciar") await renderIniciar(tabHost);
            else if (cual === "recibidas") await renderRecibidas(tabHost, () => mostrar("recibidas"));
            else await renderEnviadas(tabHost);
        } catch (e) { toastError(e); }
    }

    mount(container, el("div", { class: "wrap stack" }, [
        sectionHead("Cadena de custodia", "Transferencias"),
        tabs, tabHost
    ]));
    mostrar("recibidas");
}

async function renderIniciar(host) {
    const entradas = (await apiGet("/mis-entradas")).filter(e => e.transferible);
    if (!entradas.length) {
        mount(host, vacio("No tenés entradas transferibles.", "Solo se pueden transferir entradas EMITIDA de eventos futuros, sin transferencia pendiente y con menos de 3 transferencias."));
        return;
    }
    const select = el("select", {}, entradas.map(e =>
        el("option", { value: e.idEntrada, text: `#${String(e.idEntrada).padStart(5, "0")} · ${e.local} vs ${e.visitante} · ${e.nombreSector}` })));
    const correo = el("input", { type: "email", placeholder: "correo@destinatario.com" });
    mount(host, el("div", { class: "card stack" }, [
        el("div", { class: "field" }, [el("label", { text: "Entrada a transferir" }), select]),
        el("div", { class: "field" }, [el("label", { text: "Correo del destinatario" }), correo]),
        el("button", {
            class: "btn btn-primary", text: "Transferir",
            onclick: async () => {
                if (!correo.value.trim()) { toast("Ingresá el correo del destinatario.", "error"); return; }
                try {
                    await apiPost("/transferencias", { idEntrada: Number(select.value), destinatarioCorreo: correo.value.trim() });
                    toast("Transferencia solicitada. Espera la aceptación.", "ok", "Enviada");
                    correo.value = "";
                    renderIniciar(host);
                } catch (e) { toastError(e); }
            }
        })
    ]));
}

async function renderRecibidas(host, recargar) {
    const lista = await apiGet("/transferencias/recibidas");
    if (!lista.length) { mount(host, vacio("No tenés transferencias pendientes de aceptar.")); return; }
    mount(host, el("div", { class: "stack" }, lista.map(t => el("div", { class: "card row between" }, [
        el("div", {}, [
            el("b", { text: `${t.local} vs ${t.visitante}` }),
            el("div", { class: "tiny muted", text: `${t.estadio} · ${t.nombreSector} · ${fechaHora(t.fechaHoraInicio)}` }),
            el("div", { class: "tiny muted", text: `De: ${t.origen} · #${String(t.idEntrada).padStart(5, "0")}` })
        ]),
        el("div", { class: "row" }, [
            el("button", { class: "btn btn-primary btn-sm", text: "Aceptar", onclick: () => responder(t.idEntrada, "ACEPTAR", recargar) }),
            el("button", { class: "btn btn-danger btn-sm", text: "Rechazar", onclick: () => responder(t.idEntrada, "RECHAZAR", recargar) })
        ])
    ]))));
}

async function responder(idEntrada, decision, recargar) {
    try {
        await apiPost(`/transferencias/${idEntrada}/responder`, { decision });
        toast(decision === "ACEPTAR" ? "Transferencia aceptada. La entrada ya es tuya." : "Transferencia rechazada.", "ok");
        recargar();
    } catch (e) { toastError(e); }
}

async function renderEnviadas(host) {
    const lista = await apiGet("/transferencias/mias");
    if (!lista.length) { mount(host, vacio("No iniciaste transferencias.")); return; }
    mount(host, el("div", { class: "card" }, tabla([
        { key: "idEntrada", label: "Entrada", render: (r) => "#" + String(r.idEntrada).padStart(5, "0") },
        { key: "partido", label: "Partido", render: (r) => `${r.local} vs ${r.visitante}` },
        { key: "destinatario", label: "Para" },
        { key: "estado", label: "Estado", render: (r) => badge(r.estado) },
        { key: "fechaSolicitud", label: "Solicitada", render: (r) => fechaHora(r.fechaSolicitud) }
    ], lista)));
}
