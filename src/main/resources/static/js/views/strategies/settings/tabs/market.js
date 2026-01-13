"use strict";

window.SettingsTabTrade = (function () {

    let _inited = false;

    function init() {
        if (_inited) return;
        _inited = true;

        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const form = document.getElementById("tradeForm");
        if (!form) return;

        // UI
        const tradeModeBadge  = document.getElementById("tradeModeBadge");
        const tradeModeHint   = document.getElementById("tradeModeHint");

        const saveState   = document.getElementById("tradeSaveState");
        const saveMeta    = document.getElementById("tradeSaveMeta");
        const changedList = document.getElementById("tradeChangedList");
        const dirtyBadge  = document.getElementById("tradeDirtyBadge");

        // General tab control mode (источник правды)
        const controlModeSelect = document.getElementById("advancedControlMode");

        // Asset (из general)
        const accountAssetSelect = document.getElementById("accountAssetSelect");
        const selectedAssetView  = document.getElementById("selectedAssetView");

        // Symbol picker
        const modeGroup    = document.getElementById("tradeSymbolModes");
        const symbolList   = document.getElementById("symbolList");
        const symbolLabel  = document.getElementById("symbolLabel");
        const symbolHidden = document.getElementById("symbolHidden");

        // ✅ dropdown scroll fix
        if (symbolList) {
            symbolList.style.maxHeight = "420px";
            symbolList.style.overflowY = "auto";
            symbolList.style.overscrollBehavior = "contain";
            symbolList.setAttribute("tabindex", "0");
            symbolList.addEventListener("wheel", (e) => e.stopPropagation(), { passive: true });
            symbolList.addEventListener("touchmove", (e) => e.stopPropagation(), { passive: true });
        }

        // Timeframe
        const tfLabel    = document.getElementById("tradeTimeframeLabel");
        const tfSelect   = document.getElementById("tradeTimeframeSelect");
        const tfReadonly = document.getElementById("tradeTimeframeReadonly");
        const tfHint     = document.getElementById("tradeTimeframeHint");
        const tfAiNote   = document.getElementById("tradeAiTimeframeNote");

        // Candles
        const candlesInput = document.getElementById("tradeCachedCandlesLimit");

        // Exchange limits ui (readonly)
        const exMinNotional      = document.getElementById("exMinNotional");
        const exMinNotionalScope = document.getElementById("exMinNotionalScope");

        const exStepSize      = document.getElementById("exStepSize");
        const exStepSizeScope = document.getElementById("exStepSizeScope");

        const exTickSize      = document.getElementById("exTickSize");
        const exTickSizeScope = document.getElementById("exTickSizeScope");

        const exMaxOrders      = document.getElementById("exMaxOrders");
        const exMaxOrdersScope = document.getElementById("exMaxOrdersScope");

        // endpoints
        const AUTOSAVE_ENDPOINT = "/api/strategy/settings/autosave";
        const SYMBOLS_ENDPOINT  = "/api/market/symbols";

        // autosave engine
        const rootEl = document.querySelector(".strategy-settings-page") || form;

        const autosave = window.SettingsAutoSave?.create?.({
            rootEl,
            scope: "trade",
            context: ctx,
            endpoints: {
                autosave: AUTOSAVE_ENDPOINT,
                apply: AUTOSAVE_ENDPOINT
            },
            elements: { saveState, saveMeta, changedList, applyBtn: null },
            buildPayload
        });

        function markChanged(key) {
            if (dirtyBadge) dirtyBadge.classList.remove("d-none");
            autosave?.markChanged?.(key);
        }

        function scheduleSave(ms) {
            autosave?.scheduleSave?.(ms ?? 500);
        }

        autosave?.initReadyState?.();

        // =====================================================
        // helpers
        // =====================================================
        function isAbortError(err) {
            return err && (err.name === "AbortError" || String(err).includes("AbortError"));
        }

        function modeNow() {
            const v = (controlModeSelect?.value || "").trim();
            return v || "MANUAL";
        }

        function setModeUi() {
            const m = modeNow();

            if (tradeModeBadge) tradeModeBadge.textContent = m;

            if (tradeModeHint) {
                if (m === "AI") {
                    tradeModeHint.textContent =
                        "В AI: часть параметров может меняться в рантайме. Здесь хранится fallback и ручной выбор.";
                } else if (m === "HYBRID") {
                    tradeModeHint.textContent =
                        "В HYBRID: AI может рекомендовать, но ты можешь править вручную. Всё сохраняется автоматически.";
                } else {
                    tradeModeHint.textContent =
                        "В MANUAL: всё управляется вручную. Всё сохраняется автоматически.";
                }
            }

            if (m === "AI") {
                tfSelect?.classList.add("d-none");
                tfReadonly?.classList.remove("d-none");
                tfAiNote?.classList.remove("d-none");

                const currentTf = (tfSelect?.value || "").trim() || "—";
                if (tfReadonly) tfReadonly.textContent = currentTf;

                if (tfLabel) tfLabel.textContent = "Базовый таймфрейм (fallback)";
                if (tfHint) tfHint.textContent =
                    "В рантайме AI может использовать другой таймфрейм. В базе хранится fallback.";
            } else {
                tfReadonly?.classList.add("d-none");
                tfSelect?.classList.remove("d-none");
                tfAiNote?.classList.add("d-none");

                if (tfLabel) {
                    tfLabel.textContent = (m === "HYBRID")
                        ? "Таймфрейм (AI может рекомендовать)"
                        : "Таймфрейм стратегии";
                }
                if (tfHint) {
                    tfHint.textContent = (m === "HYBRID")
                        ? "AI может рекомендовать, но менять можно вручную."
                        : "Используется стратегией как основной таймфрейм.";
                }
            }
        }

        function normalizeSymbol(sym) {
            if (!sym) return "";
            return String(sym).trim().toUpperCase();
        }

        function fmtBdOrNull(v) {
            if (v == null) return null;
            const s = String(v).trim();
            return s === "" || s === "null" ? null : s;
        }

        function fmtWithUnit(valueStr, unit) {
            if (!valueStr) return "—";
            const u = (unit || "").trim().toUpperCase();
            return u ? `${valueStr} ${u}` : valueStr;
        }

        // ✅ ВАЖНО: НЕ берём ctx.accountAsset первым, он “залипает”
        function getAssetForRequest() {
            const a1 = (accountAssetSelect?.value || "").trim();
            if (a1) return a1.toUpperCase();

            const a2 = (selectedAssetView?.textContent || "").trim();
            if (a2 && a2 !== "—") return a2.toUpperCase();

            const a0 = String(ctx.accountAsset || "").trim();
            if (a0) return a0.toUpperCase();

            return "USDT";
        }

        function setLimitsUiEmpty() {
            if (exMinNotional) exMinNotional.value = "—";
            if (exMinNotionalScope) exMinNotionalScope.textContent = "—";

            if (exStepSize) exStepSize.value = "—";
            if (exStepSizeScope) exStepSizeScope.textContent = "—";

            if (exTickSize) exTickSize.value = "—";
            if (exTickSizeScope) exTickSizeScope.textContent = "—";

            if (exMaxOrders) exMaxOrders.value = "—";
            if (exMaxOrdersScope) exMaxOrdersScope.textContent = "—";
        }

        function applyLimitsFromDescriptor(d) {
            if (!d) return setLimitsUiEmpty();

            const quote = String(d.quoteAsset || getAssetForRequest() || "").trim().toUpperCase();
            const base  = String(d.baseAsset || "").trim().toUpperCase();

            const mn = fmtBdOrNull(d.minNotional);
            if (exMinNotional) exMinNotional.value = fmtWithUnit(mn, quote);
            if (exMinNotionalScope) exMinNotionalScope.textContent = (d.minNotionalScope ?? "—");

            const ss = fmtBdOrNull(d.stepSize);
            if (exStepSize) exStepSize.value = fmtWithUnit(ss, base);
            if (exStepSizeScope) exStepSizeScope.textContent = (d.stepSizeScope ?? "—");

            const ts = fmtBdOrNull(d.tickSize);
            if (exTickSize) exTickSize.value = fmtWithUnit(ts, quote);
            if (exTickSizeScope) exTickSizeScope.textContent = (d.tickSizeScope ?? "—");

            if (exMaxOrders) exMaxOrders.value = (d.maxOrders != null ? String(d.maxOrders) : "—");
            if (exMaxOrdersScope) exMaxOrdersScope.textContent = (d.maxOrdersScope ?? "—");
        }

        function buildPayload() {
            return {
                chatId: ctx.chatId,
                type: ctx.type,
                exchange: ctx.exchange || "BINANCE",
                network: ctx.network || "TESTNET",
                scope: "trade",
                symbol: normalizeSymbol(symbolHidden?.value || ""),
                timeframe: (tfSelect?.value || "").trim() || null,
                cachedCandlesLimit: (candlesInput?.value || "").trim() || null
            };
        }

        // =====================================================
        // symbols api (Abort-safe + race-safe)
        // =====================================================
        let activeMode = "POPULAR";
        let lastMap = new Map(); // SYMBOL -> descriptor

        const SymbolsRequest = {
            controller: null,
            seq: 0
        };

        async function fetchSymbols(mode) {
            const exchange = String(ctx.exchange || "BINANCE").trim() || "BINANCE";
            const network  = String(ctx.network  || "TESTNET").trim() || "TESTNET";
            const asset    = getAssetForRequest();

            const url =
                `${SYMBOLS_ENDPOINT}` +
                `?exchange=${encodeURIComponent(exchange)}` +
                `&network=${encodeURIComponent(network)}` +
                `&accountAsset=${encodeURIComponent(asset)}` +
                `&mode=${encodeURIComponent(mode || "POPULAR")}`;

            // abort previous
            if (SymbolsRequest.controller) {
                try { SymbolsRequest.controller.abort("new symbols request"); }
                catch (_) { SymbolsRequest.controller.abort(); }
            }

            const controller = new AbortController();
            SymbolsRequest.controller = controller;
            const mySeq = ++SymbolsRequest.seq;

            const res = await fetch(url, {
                method: "GET",
                headers: { "Accept": "application/json" },
                signal: controller.signal
            });

            // устаревший запрос — игнор
            if (mySeq !== SymbolsRequest.seq) return null;

            if (!res.ok) throw new Error(`symbols http ${res.status}`);
            const data = await res.json();

            // и тут тоже защита от гонок
            if (mySeq !== SymbolsRequest.seq) return null;

            return data;
        }

        function rebuildMap(items) {
            lastMap = new Map();
            if (!Array.isArray(items)) return;
            for (const it of items) {
                const sym = normalizeSymbol(it?.symbol);
                if (sym) lastMap.set(sym, it);
            }
        }

        function renderSymbolList(items) {
            if (!symbolList) return;
            symbolList.innerHTML = "";

            if (!Array.isArray(items) || items.length === 0) {
                const li = document.createElement("li");
                li.className = "dropdown-item text-muted";
                li.textContent = "Нет данных";
                symbolList.appendChild(li);
                return;
            }

            const current = normalizeSymbol(symbolHidden?.value || "");

            for (const it of items) {
                const sym = normalizeSymbol(it.symbol);

                const li = document.createElement("li");
                const btn = document.createElement("button");
                btn.type = "button";
                btn.className = "dropdown-item d-flex justify-content-between align-items-center gap-2";

                const left = document.createElement("span");
                left.textContent = sym || "—";

                const right = document.createElement("span");
                right.className = "small text-secondary";

                const lp  = (it.lastPrice != null) ? String(it.lastPrice) : "";
                const ch  = (it.priceChangePct24h != null) ? String(it.priceChangePct24h) : "";
                const vol = (it.volume24h != null) ? String(it.volume24h) : "";

                const parts = [];
                if (lp) parts.push(lp);
                if (ch) parts.push(ch + "%");
                if (vol) parts.push("vol " + vol);
                right.textContent = parts.join(" · ");

                btn.appendChild(left);
                btn.appendChild(right);

                if (sym && sym === current) {
                    const badge = document.createElement("span");
                    badge.className = "badge bg-primary ms-2";
                    badge.textContent = "Выбрано";
                    btn.appendChild(badge);
                }

                btn.addEventListener("click", async () => {
                    await selectSymbol(sym);
                });

                li.appendChild(btn);
                symbolList.appendChild(li);
            }
        }

        async function reloadSymbols() {
            try {
                const items = await fetchSymbols(activeMode);

                // null = устаревший ответ (или запрос отменён другим) — тихо выходим
                if (items == null) return;

                rebuildMap(items);
                renderSymbolList(items);

                const cur = normalizeSymbol(symbolHidden?.value || "");
                if (cur && lastMap.has(cur)) applyLimitsFromDescriptor(lastMap.get(cur));
                else setLimitsUiEmpty();

            } catch (e) {
                // AbortError — это ожидаемо, не ошибка
                if (isAbortError(e)) return;

                console.error("reloadSymbols failed", e);
                rebuildMap([]);
                renderSymbolList([]);
                setLimitsUiEmpty();
            }
        }

        function setSymbolUi(sym) {
            const s = normalizeSymbol(sym);
            if (symbolLabel) symbolLabel.textContent = s || "Выберите торговую пару";
            if (symbolHidden) symbolHidden.value = s || "";
        }

        async function selectSymbol(sym) {
            const s = normalizeSymbol(sym);
            if (!s) return;

            setSymbolUi(s);
            markChanged("symbol");

            applyLimitsFromDescriptor(lastMap.get(s) || null);

            scheduleSave(350);
        }

        // =====================================================
        // ✅ СИНХРОНИЗАЦИЯ АКТИВА (без двойного reload и без циклов)
        // =====================================================
        let _lastAsset = "";

        function notifyAssetChanged(asset, opts) {
            const a = (asset || "").trim().toUpperCase();
            if (!a) return;

            // если не изменился — ничего не делаем
            if (a === _lastAsset) return;
            _lastAsset = a;

            // обновляем ctx как кэш
            ctx.accountAsset = a;

            // silent = только обновить кэш без оповещения
            if (opts?.silent) return;

            window.dispatchEvent(new CustomEvent("strategy:asset-changed", { detail: { asset: a } }));
        }

        // 1) если актив меняется через select
        if (accountAssetSelect) {
            accountAssetSelect.addEventListener("change", () => {
                const a = (accountAssetSelect.value || "").trim();
                // ✅ НЕ вызываем reloadSymbols тут — оно будет по событию один раз
                notifyAssetChanged(a);
            });
        }

        // 2) если актив меняется иначе (General/другие вкладки)
        window.addEventListener("strategy:asset-changed", async (e) => {
            const a = e?.detail?.asset;
            if (!a) return;

            // если событие пришло с тем же активом — выходим
            const next = String(a).trim().toUpperCase();
            if (!next || next === _lastAsset) return;

            notifyAssetChanged(next, { silent: true });
            await reloadSymbols();
        });

        // =====================================================
        // binds
        // =====================================================
        if (modeGroup) {
            modeGroup.addEventListener("click", async (e) => {
                const btn = e.target?.closest?.("button[data-symbol-mode]");
                if (!btn) return;

                activeMode = (btn.dataset.symbolMode || "POPULAR").trim();

                for (const b of modeGroup.querySelectorAll("button[data-symbol-mode]")) {
                    b.classList.toggle("active", b === btn);
                }

                await reloadSymbols();
            });
        }

        if (tfSelect) {
            tfSelect.addEventListener("change", async () => {
                if (modeNow() === "AI") {
                    setModeUi();
                    return;
                }
                markChanged("timeframe");
                scheduleSave(350);
            });
        }

        if (candlesInput) {
            let t = null;
            candlesInput.addEventListener("input", () => {
                markChanged("cachedCandlesLimit");
                if (t) clearTimeout(t);
                t = setTimeout(() => scheduleSave(200), 600);
            });
            candlesInput.addEventListener("change", () => {
                markChanged("cachedCandlesLimit");
                scheduleSave(200);
            });
        }

        controlModeSelect?.addEventListener("change", () => setModeUi());

        // =====================================================
        // first load
        // =====================================================
        setModeUi();

        setSymbolUi(symbolHidden?.value || symbolLabel?.textContent || "");
        setLimitsUiEmpty();

        // ✅ при старте: только обновляем ctx, без события (чтобы не словить лишний reload)
        notifyAssetChanged(getAssetForRequest(), { silent: true });

        // и один нормальный загрузочный запрос
        reloadSymbols().catch(() => {});
    }

    return { init };
})();

// алиас для совместимости, если где-то осталось старое имя
window.SettingsTabMarket = window.SettingsTabTrade;
