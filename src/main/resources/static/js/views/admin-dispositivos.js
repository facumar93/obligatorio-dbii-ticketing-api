// Vista admin: dispositivos de validación, vinculación con funcionarios y asignación a evento/sector.
import { apiGet, apiPost } from "../api.js";
import { el, mount, tabla, badge, cargando, vacio, toast, toastError, sectionHead } from "../ui.js";

export async function render(container) {
    mount(container, el("div", { class: "wrap stack" }, [sectionHead("Administración", "Dispositivos y validadores"), cargando(2)]));

    async function recargar() {
        let dispositivos, validadores, eventos;
        try {
            [dispositivos, validadores, eventos] = await Promise.all([
                apiGet("/admin/dispositivos"), apiGet("/admin/validadores"), apiGet("/eventos")
            ]);
        } catch (e) { toastError(e); return; }

        const libres = dispositivos.filter(d => d.estado === "ACTIVO" && !d.idVinculacionActiva);
        const vinculados = dispositivos.filter(d => d.idVinculacionActiva);

        // --- Card dispositivos ---
        const cardDispositivos = el("div", { class: "card stack" }, [
            el("div", { class: "row between" }, [el("div", { class: "kicker", text: "Dispositivos" }),
                el("button", { class: "btn btn-primary btn-sm", text: "Registrar dispositivo", onclick: registrar })]),
            dispositivos.length ? tabla([
                { key: "idDispositivo", label: "ID", num: true },
                { key: "estado", label: "Estado", render: (r) => badge(r.estado) },
                { key: "validador", label: "Validador", render: (r) => r.validador || "—" }
            ], dispositivos) : vacio("Sin dispositivos.")
        ]);

        // --- Card vincular ---
        const selVal = el("select", {}, [el("option", { value: "", text: "Funcionario…" }),
            ...validadores.map(v => el("option", { value: v.idUsuario, text: `${v.nombre} ${v.apellido} (leg. ${v.numeroLegajo})` }))]);
        const selDisp = el("select", {}, [el("option", { value: "", text: "Dispositivo libre…" }),
            ...libres.map(d => el("option", { value: d.idDispositivo, text: `#${d.idDispositivo}` }))]);
        const cardVincular = el("div", { class: "card stack" }, [
            el("div", { class: "kicker", text: "Vincular funcionario ↔ dispositivo" }),
            validadores.length ? null : el("p", { class: "tiny muted", text: "No hay funcionarios de validación cargados (ver seed)." }),
            el("div", { class: "grid cols-2" }, [
                el("div", { class: "field" }, [el("label", { text: "Funcionario" }), selVal]),
                el("div", { class: "field" }, [el("label", { text: "Dispositivo" }), selDisp])
            ]),
            el("button", {
                class: "btn btn-accent", text: "Vincular",
                onclick: async () => {
                    if (!selVal.value || !selDisp.value) { toast("Elegí funcionario y dispositivo.", "error"); return; }
                    try {
                        await apiPost("/admin/vinculaciones", { idUsuarioValidador: Number(selVal.value), idDispositivo: Number(selDisp.value) });
                        toast("Vinculación creada.", "ok"); recargar();
                    } catch (e) { toastError(e); }
                }
            })
        ]);

        // --- Card asignar a evento/sector ---
        const selVinc = el("select", {}, [el("option", { value: "", text: "Vinculación activa…" }),
            ...vinculados.map(d => el("option", { value: d.idVinculacionActiva, text: `Disp #${d.idDispositivo} · ${d.validador}` }))]);
        const selEvento = el("select", {}, [el("option", { value: "", text: "Evento…" }),
            ...eventos.map(ev => el("option", { value: ev.idEvento, text: `${ev.local} vs ${ev.visitante}` }))]);
        const selSector = el("select", {}, [el("option", { value: "", text: "Elegí evento primero" })]);
        selEvento.addEventListener("change", async () => {
            if (!selEvento.value) return;
            try {
                const det = await apiGet(`/eventos/${selEvento.value}`);
                mount(selSector, el("option", { value: "", text: "Sector…" }),
                    ...det.sectores.map(s => el("option", { value: s.idSector, text: s.nombreSector })));
            } catch (e) { toastError(e); }
        });
        const cardAsignar = el("div", { class: "card stack" }, [
            el("div", { class: "kicker", text: "Asignar dispositivo a evento/sector" }),
            vinculados.length ? null : el("p", { class: "tiny muted", text: "Primero vinculá un dispositivo a un funcionario." }),
            el("div", { class: "grid cols-3" }, [
                el("div", { class: "field" }, [el("label", { text: "Vinculación" }), selVinc]),
                el("div", { class: "field" }, [el("label", { text: "Evento" }), selEvento]),
                el("div", { class: "field" }, [el("label", { text: "Sector" }), selSector])
            ]),
            el("button", {
                class: "btn btn-accent", text: "Asignar",
                onclick: async () => {
                    if (!selVinc.value || !selEvento.value || !selSector.value) { toast("Completá vinculación, evento y sector.", "error"); return; }
                    try {
                        await apiPost("/admin/asignaciones-dispositivo", { idVinculacion: Number(selVinc.value), idEvento: Number(selEvento.value), idSector: Number(selSector.value) });
                        toast("Dispositivo asignado al evento/sector.", "ok"); recargar();
                    } catch (e) { toastError(e); }
                }
            })
        ]);

        mount(container, el("div", { class: "wrap stack" }, [
            sectionHead("Administración", "Dispositivos y validadores"),
            cardDispositivos, cardVincular, cardAsignar
        ]));
    }

    async function registrar() {
        try { const r = await apiPost("/admin/dispositivos", {}); toast(`Dispositivo #${r.idDispositivo} registrado.`, "ok"); recargar(); }
        catch (e) { toastError(e); }
    }

    recargar();
}
