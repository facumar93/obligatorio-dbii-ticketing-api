// QR dinámico: modal con código que muta cada 30s + anillo de countdown.
// Usa qrcode-generator por CDN (la app ya depende de internet para Firebase).
import { apiGet } from "./api.js";
import { el, modal, toastError } from "./ui.js";

let qrLibPromise = null;
function cargarLibQr() {
    if (window.qrcode) return Promise.resolve();
    if (qrLibPromise) return qrLibPromise;
    qrLibPromise = new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.js";
        s.onload = resolve;
        s.onerror = reject;
        document.head.appendChild(s);
    });
    return qrLibPromise;
}

export async function mostrarQr(ent) {
    try { await cargarLibQr(); } catch { /* fallback a texto */ }

    const imgWrap = el("div", { class: "qr-canvas-wrap" });
    const ring = el("div", { class: "qr-ring" }, imgWrap);
    const count = el("div", { class: "qr-count", text: "—" });
    const tokenEl = el("div", { class: "qr-token", text: "" });

    const content = el("div", { class: "stack" }, [
        el("div", { class: "kicker", text: `Entrada #${String(ent.idEntrada).padStart(5, "0")}` }),
        el("div", { class: "h2", text: "QR dinámico" }),
        el("div", { class: "qr-frame" }, [ring, count, tokenEl]),
        el("p", { class: "tiny muted center", text: "El código muta cada 30 segundos. No compartas capturas." })
    ]);

    let timer = null;
    const m = modal(content, { onClose: () => clearInterval(timer) });

    function dibujar(token) {
        if (window.qrcode) {
            const qr = window.qrcode(0, "M");
            qr.addData(token);
            qr.make();
            imgWrap.innerHTML = qr.createImgTag(5, 8);
            const img = imgWrap.querySelector("img");
            if (img) { img.style.width = "100%"; img.style.height = "100%"; img.style.imageRendering = "pixelated"; }
        } else {
            imgWrap.replaceChildren(el("div", { class: "tiny center", text: "QR no disponible. Usá el código de abajo." }));
        }
    }

    async function refrescar() {
        try {
            const r = await apiGet(`/entradas/${ent.idEntrada}/qr`);
            if (!r.token) {
                imgWrap.replaceChildren(el("div", { class: "tiny center", text: r.message || "Entrada no vigente" }));
                count.textContent = "—"; tokenEl.textContent = "";
                clearInterval(timer);
                return;
            }
            dibujar(r.token);
            tokenEl.textContent = r.token;
            let restante = Number(r.expiraEnSegundos) || 30;
            const pintar = () => {
                count.textContent = Math.max(restante, 0) + "s";
                ring.style.setProperty("--p", Math.max(restante, 0) / 30);
            };
            pintar();
            clearInterval(timer);
            timer = setInterval(() => {
                restante--;
                pintar();
                if (restante <= 0) { clearInterval(timer); refrescar(); }
            }, 1000);
        } catch (e) {
            clearInterval(timer);
            toastError(e);
            m.close();
        }
    }

    refrescar();
}
