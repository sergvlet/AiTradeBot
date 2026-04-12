"use strict";

/**
 * Universal form change tracker with confirm modal support + mode locks.
 * - no native window.confirm if modal exists (confirmModal/generalConfirmModal)
 * - works even if bootstrap JS отсутствует (manual modal fallback)
 * - blocks autosave listeners until user confirms (capture + stopImmediatePropagation)
 * - supports locking fields in AI/HYBRID via data-lock-mode
 */
window.FormChangeTracker = (function () {

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function byId(id) { return document.getElementById(id); }

    // =====================================================
    // Modal helpers
    // =====================================================
    function hasBootstrapModal() {
        return !!(window.bootstrap && window.bootstrap.Modal);
    }

    function cleanupBackdrops() {
        try {
            document.querySelectorAll(".modal-backdrop").forEach(b => b.remove());
            document.body.classList.remove("modal-open");
            document.body.style.removeProperty("padding-right");
        } catch (_) {}
    }

    function resolveModalEls() {
        // ✅ ищем ТОЛЬКО реальную модалку (class="modal")
        const modal =
            document.querySelector("#confirmModal.modal")
            || document.querySelector("#generalConfirmModal.modal")
            || null;

        // ✅ поддержка обоих наборов id
        const titleEl =
            byId("confirmModalTitle")
            || byId("generalConfirmTitle")
            || null;

        const textEl =
            byId("confirmModalBody")
            || byId("generalConfirmText")
            || null;

        const okBtn =
            byId("confirmModalOk")
            || byId("generalConfirmOk")
            || null;

        const cancelBtn =
            byId("confirmModalCancel")
            || byId("generalConfirmCancel")
            || null;

        return { modal, titleEl, textEl, okBtn, cancelBtn };
    }

    function getBootstrapInstance(modalEl) {
        if (!modalEl || !hasBootstrapModal()) return null;
        try {
            return window.bootstrap.Modal.getOrCreateInstance(modalEl, { backdrop: "static", keyboard: true });
        } catch (_) {
            try { return new window.bootstrap.Modal(modalEl, { backdrop: "static", keyboard: true }); }
            catch (_) { return null; }
        }
    }

    // manual fallback (если bootstrap js нет)
    function manualShow(modalEl) {
        if (!modalEl) return;
        cleanupBackdrops();

        modalEl.style.display = "block";
        modalEl.classList.add("show");
        modalEl.removeAttribute("aria-hidden");
        modalEl.setAttribute("aria-modal", "true");
        document.body.classList.add("modal-open");

        const bd = document.createElement("div");
        bd.className = "modal-backdrop fade show";
        document.body.appendChild(bd);
    }

    function manualHide(modalEl) {
        if (!modalEl) return;
        modalEl.classList.remove("show");
        modalEl.style.display = "none";
        cleanupBackdrops();
    }

    // guard against двойной вызов
    let confirmInProgress = false;

    /**
     * openConfirm
     * - If modal exists -> uses it (bootstrap or manual)
     * - If modal отсутствует -> fallback to window.confirm (крайний случай)
     */
    function openConfirm(title, text, onOk, onCancel) {
        const els = resolveModalEls();

        const t = isBlank(title) ? "Подтверждение" : String(title);
        const msg = isBlank(text) ? "Сохранить изменения?" : String(text);

        // ✅ если модалки вообще нет — только тогда native confirm (крайний fallback)
        if (!els.modal || !els.okBtn) {
            const ok = window.confirm(`${t}\n\n${msg}`);
            if (ok) onOk && onOk();
            else onCancel && onCancel();
            return;
        }

        // если уже открыта (например два change подряд) — игнорируем второй
        if (confirmInProgress) return;
        confirmInProgress = true;

        if (els.titleEl) els.titleEl.textContent = t;
        if (els.textEl) els.textEl.textContent = msg;

        cleanupBackdrops();

        let done = false;
        let bs = null;

        const finish = (ok) => {
            if (done) return;
            done = true;

            try { els.okBtn.removeEventListener("click", onOkClick, true); } catch (_) {}
            try { els.cancelBtn?.removeEventListener("click", onCancelClick, true); } catch (_) {}
            try { els.modal.removeEventListener("hidden.bs.modal", onHidden); } catch (_) {}
            try { els.modal.removeEventListener("click", onOverlayClick, true); } catch (_) {}
            try { document.removeEventListener("keydown", onEsc, true); } catch (_) {}

            // закрытие
            try {
                if (bs) bs.hide();
                else manualHide(els.modal);
            } catch (_) {
                manualHide(els.modal);
            }

            confirmInProgress = false;

            if (ok) onOk && onOk();
            else onCancel && onCancel();
        };

        const onOkClick = (e) => {
            try { e.preventDefault(); } catch (_) {}
            finish(true);
        };

        const onCancelClick = (e) => {
            try { e.preventDefault(); } catch (_) {}
            finish(false);
        };

        // bootstrap hidden => cancel (если закрыли крестиком/ESC/backdrop)
        const onHidden = () => finish(false);

        // manual ESC => cancel
        const onEsc = (e) => {
            if (e && e.key === "Escape") finish(false);
        };

        // manual click outside (если вдруг)
        const onOverlayClick = (e) => {
            // если кликнули по самой модалке-оверлею (а не внутри content)
            if (e && e.target === els.modal) finish(false);
        };

        // listeners (capture, чтобы не пропускать клики дальше)
        els.okBtn.addEventListener("click", onOkClick, true);
        if (els.cancelBtn) els.cancelBtn.addEventListener("click", onCancelClick, true);

        // show
        if (hasBootstrapModal()) {
            bs = getBootstrapInstance(els.modal);
            if (!bs) {
                // bootstrap есть, но сломан -> manual
                manualShow(els.modal);
                els.modal.addEventListener("click", onOverlayClick, true);
                document.addEventListener("keydown", onEsc, true);
            } else {
                els.modal.addEventListener("hidden.bs.modal", onHidden);
                try { bs.show(); } catch (_) { manualShow(els.modal); }
            }
        } else {
            manualShow(els.modal);
            els.modal.addEventListener("click", onOverlayClick, true);
            document.addEventListener("keydown", onEsc, true);
        }
    }

    // =====================================================
    // Confirm binding
    // =====================================================

    function getElValueForCompare(el) {
        if (!el) return "";
        const type = String(el.type || "").toLowerCase();
        if (type === "checkbox") return String(!!el.checked);
        if (type === "radio") return String(!!el.checked);
        return String(el.value ?? "");
    }

    function setElValueFromString(el, s) {
        if (!el) return;
        const type = String(el.type || "").toLowerCase();
        if (type === "checkbox") el.checked = (String(s) === "true");
        else if (type === "radio") el.checked = (String(s) === "true");
        else el.value = String(s ?? "");
    }

    function dispatch(el, name, detail) {
        if (!el) return;
        try {
            const ev = new CustomEvent(name, { bubbles: true, detail: detail || {} });
            el.dispatchEvent(ev);
        } catch (_) {
            try {
                const ev2 = new Event(name, { bubbles: true });
                el.dispatchEvent(ev2);
            } catch (_) {}
        }
    }

    /**
     * bindConfirm:
     * - elements with data-confirm="true" will ask confirm on change/blur
     * - blocks other change listeners until confirmed (capture + stopImmediatePropagation)
     */
    function bindConfirm(rootEl) {
        const root = rootEl || document;

        // ✅ чуть шире: true/1/yes/on
        const items = root.querySelectorAll(
            "[data-confirm='true'],[data-confirm='1'],[data-confirm='yes'],[data-confirm='on']"
        );

        items.forEach(el => {
            if (!el || el.dataset.confirmBound === "1") return;
            el.dataset.confirmBound = "1";

            // init prev
            const initial = getElValueForCompare(el);
            el.setAttribute("data-prev", initial);

            const handler = (e) => {
                // ignore synthetic (мы сами диспатчим change после OK)
                if (e && e.isTrusted === false) return;

                // если поле залочено — вообще не вмешиваемся
                if (el.disabled || el.readOnly) return;

                // bypass (после OK можно дернуть обычный change без цикла)
                if (el.dataset.confirmBypass === "1") {
                    el.dataset.confirmBypass = "0";
                    el.setAttribute("data-prev", getElValueForCompare(el));
                    return;
                }

                const prev = el.getAttribute("data-prev") ?? "";
                const now  = getElValueForCompare(el);
                if (prev === now) return;

                // IMPORTANT: block autosave/change handlers
                try { e.preventDefault(); } catch (_) {}
                try { e.stopPropagation(); } catch (_) {}
                try { e.stopImmediatePropagation(); } catch (_) {}

                const title = el.getAttribute("data-confirm-title") || "Подтверждение";
                const text  = el.getAttribute("data-confirm-text")  || "Сохранить изменения?";

                openConfirm(title, text, () => {
                    // OK
                    el.setAttribute("data-prev", now);

                    // allow real change listeners to run once
                    el.dataset.confirmBypass = "1";

                    dispatch(el, "confirmed-change", { prev, now });

                    // real change (autosave-friendly)
                    try { el.dispatchEvent(new Event("change", { bubbles: true })); } catch (_) {}
                }, () => {
                    // CANCEL -> revert
                    setElValueFromString(el, prev);
                    dispatch(el, "confirm-cancel", { prev, now });
                });
            };

            // confirm on change (capture so we block autosave)
            el.addEventListener("change", handler, true);

            // number inputs: удобно подтверждать на blur
            if (el.tagName === "INPUT" && String(el.type || "").toLowerCase() === "number") {
                el.addEventListener("blur", handler, true);
            }
        });
    }

    /**
     * Sync data-prev to current values to avoid confirmation after programmatic UI updates.
     * (does not touch focused element)
     */
    function syncPrevValues(rootEl) {
        const root = rootEl || document;
        const items = root.querySelectorAll(
            "[data-confirm='true'],[data-confirm='1'],[data-confirm='yes'],[data-confirm='on']"
        );
        items.forEach(el => {
            if (!el) return;
            if (document.activeElement === el) return;
            el.setAttribute("data-prev", getElValueForCompare(el));
        });
    }

    // =====================================================
    // Mode locks (AI/HYBRID)
    // =====================================================

    function parseLockModes(raw) {
        if (isBlank(raw)) return [];
        return String(raw)
            .split(/[,;\s]+/g)
            .map(x => String(x || "").trim().toUpperCase())
            .filter(Boolean);
    }

    function shouldLock(el, mode) {
        const raw = el.getAttribute("data-lock-mode");
        if (isBlank(raw)) return false;
        const list = parseLockModes(raw);
        const m = String(mode || "").trim().toUpperCase();
        return list.includes(m);
    }

    function saveOrigState(el) {
        if (!el) return;
        if (el.dataset.origDisabled === undefined) el.dataset.origDisabled = String(!!el.disabled);
        if (el.dataset.origReadonly === undefined) el.dataset.origReadonly = String(!!el.readOnly);
    }

    function restoreOrigState(el) {
        if (!el) return;
        if (el.dataset.origDisabled !== undefined) el.disabled = (el.dataset.origDisabled === "true");
        if (el.dataset.origReadonly !== undefined) el.readOnly = (el.dataset.origReadonly === "true");
    }

    function applyLock(el, lockType) {
        if (!el) return;

        saveOrigState(el);

        const tag = String(el.tagName || "").toUpperCase();
        const type = String(lockType || "").toLowerCase();

        const isTextLike = (tag === "INPUT" || tag === "TEXTAREA");
        const defaultType = isTextLike ? "readonly" : "disabled";
        const t = type || defaultType;

        if (t === "readonly") {
            if ("readOnly" in el) el.readOnly = true;
            else el.disabled = true;
        } else if (t === "disabled") {
            el.disabled = true;
        } else if (t === "both") {
            el.disabled = true;
            if ("readOnly" in el) el.readOnly = true;
        } else {
            el.disabled = true;
        }
    }

    function applyUnlock(el) {
        if (!el) return;
        restoreOrigState(el);
    }

    /**
     * Apply locks for elements with data-lock-mode.
     */
    function applyLocks(rootEl, mode) {
        const root = rootEl || document;
        const m = String(mode || "").trim().toUpperCase();

        const items = root.querySelectorAll("[data-lock-mode]");
        items.forEach(el => {
            const lock = shouldLock(el, m);
            if (lock) {
                const lt = el.getAttribute("data-lock-type");
                applyLock(el, lt);
            } else {
                applyUnlock(el);
            }
        });
    }

    // =====================================================
    // Optional init: hooks to your events
    // =====================================================
    let hooked = false;

    function extractModeFromState(st) {
        if (!st) return "MANUAL";
        // иногда advancedControlMode приходит строкой или объектом
        const m = st.advancedControlMode || st.controlMode || st.mode || null;
        if (!m) return "MANUAL";
        if (typeof m === "object" && m.name) return String(m.name).toUpperCase();
        return String(m).toUpperCase();
    }

    function hookGlobalListeners() {
        if (hooked) return;
        hooked = true;

        // старое
        window.addEventListener("strategy:controlModeChanged", (e) => {
            const mode = (e?.detail?.mode || window.__StrategyControlMode || "MANUAL");
            applyLocks(document, mode);
            syncPrevValues(document);
        });

        window.addEventListener("strategy:uiStateChanged", (e) => {
            const st = e?.detail?.state || e?.detail || null;
            const mode = extractModeFromState(st) || window.__StrategyControlMode || "MANUAL";
            applyLocks(document, mode);
            syncPrevValues(document);
        });

        // ✅ новое (из твоего page.js / Bus)
        window.addEventListener("ui:state", (e) => {
            const st = e?.detail || null;
            const mode = extractModeFromState(st) || window.__StrategyControlMode || "MANUAL";
            applyLocks(document, mode);
            syncPrevValues(document);
        });

        window.addEventListener("strategy:state", (e) => {
            const st = e?.detail || null;
            const mode = extractModeFromState(st) || window.__StrategyControlMode || "MANUAL";
            applyLocks(document, mode);
            syncPrevValues(document);
        });
    }

    return {
        bindConfirm,
        syncPrevValues,
        applyLocks,
        hookGlobalListeners
    };
})();
