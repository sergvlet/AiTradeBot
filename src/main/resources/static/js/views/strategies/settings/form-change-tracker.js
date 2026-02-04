"use strict";

/**
 * Universal form change tracker with confirm modal support.
 * - does not break if bootstrap modal отсутствует
 * - safe for SPA-like autosave pages
 */
window.FormChangeTracker = (function () {

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function byId(id) { return document.getElementById(id); }

    function getModal() {
        const el = byId("generalConfirmModal");
        if (!el) return null;

        // bootstrap may be missing in some pages
        if (!window.bootstrap || !bootstrap.Modal) return { el, show: () => {}, hide: () => {} };

        try {
            return bootstrap.Modal.getOrCreateInstance(el);
        } catch (_) {
            try { return new bootstrap.Modal(el); } catch (_) { return { el, show: () => {}, hide: () => {} }; }
        }
    }

    function openConfirm(text, onOk, onCancel) {
        const modal = getModal();
        const txt = byId("generalConfirmText");
        const okBtn = byId("generalConfirmOk");
        const cancelBtn = byId("generalConfirmCancel");

        if (txt) txt.textContent = isBlank(text) ? "Сохранить изменения?" : String(text);

        // если модалки нет — просто подтверждение браузера
        if (!modal || !modal.show || !okBtn) {
            if (window.confirm(txt ? txt.textContent : "Сохранить изменения?")) onOk && onOk();
            else onCancel && onCancel();
            return;
        }

        const cleanup = () => {
            okBtn.onclick = null;
            if (cancelBtn) cancelBtn.onclick = null;
        };

        okBtn.onclick = () => {
            cleanup();
            try { modal.hide(); } catch (_) {}
            onOk && onOk();
        };

        if (cancelBtn) {
            cancelBtn.onclick = () => {
                cleanup();
                try { modal.hide(); } catch (_) {}
                onCancel && onCancel();
            };
        }

        try { modal.show(); } catch (_) {}
    }

    /**
     * bind confirm behaviour:
     * - elements with data-confirm="true" will ask confirm on change/blur
     */
    function bindConfirm(rootEl) {
        const root = rootEl || document;
        const items = root.querySelectorAll("[data-confirm='true']");

        items.forEach(el => {
            const title = el.getAttribute("data-confirm-title") || "Подтверждение";
            const text  = el.getAttribute("data-confirm-text")  || "Сохранить изменения?";

            const handler = (e) => {
                // если value реально не менялся — ничего не делаем
                // (простая защита от лишних подтверждений)
                const prev = el.getAttribute("data-prev") || "";
                const now  = (el.type === "checkbox") ? String(!!el.checked) : String(el.value ?? "");
                if (prev === now) return;

                e.preventDefault();

                openConfirm(`${title}\n\n${text}`, () => {
                    el.setAttribute("data-prev", now);
                    // триггерим change ещё раз уже “разрешённый”
                    try {
                        const ev = new Event("confirmed-change", { bubbles: true });
                        el.dispatchEvent(ev);
                    } catch (_) {}
                }, () => {
                    // откат назад
                    if (el.type === "checkbox") el.checked = (prev === "true");
                    else el.value = prev;
                });
            };

            // init prev
            const initial = (el.type === "checkbox") ? String(!!el.checked) : String(el.value ?? "");
            el.setAttribute("data-prev", initial);

            // confirm only on change (и на blur для number)
            el.addEventListener("change", handler);
            if (el.tagName === "INPUT" && el.type === "number") {
                el.addEventListener("blur", handler);
            }
        });
    }

    return { bindConfirm };
})();
