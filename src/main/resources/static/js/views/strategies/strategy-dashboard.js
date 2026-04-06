"use strict";

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer } from "../../chart/layer-renderer.js";

import { ScalpingStrategy } from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy } from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";
import { EmaStrategy } from "../../strategies/ema.strategy.js";

class GenericStrategy {
    constructor({ layers, ctx }) {
        this.layers = layers;
        this.ctx = ctx;
    }
    onEvent(_) {}
    onCandleHistory(_) {}
    setInfo(_) {}
}

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard START");

    const root = document.querySelector(
        "[data-chat-id][data-type][data-symbol][data-exchange][data-network]"
    );
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = String(root.dataset.chatId || "").trim();
    const type = String(root.dataset.type || "").trim().toUpperCase();
    const symbol = String(root.dataset.symbol || "").trim().toUpperCase();
    const exchange = String(root.dataset.exchange || "").trim().toUpperCase();
    const network = String(root.dataset.network || "").trim().toUpperCase();
    const timeframe = String(root.dataset.timeframe || "1m").trim().toLowerCase();
    const pnlAsset = String(root.dataset.pnlAsset || "").trim().toUpperCase() || detectQuoteAsset(symbol);

    if (!chatId || !type || !symbol) {
        console.error("❌ Invalid dashboard context", { chatId, type, symbol });
        return;
    }

    console.log("🧩 Context:", { chatId, type, symbol, exchange, network, timeframe, pnlAsset });

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ #strategy-chart not found");
        return;
    }

    const wsStatusEl = document.getElementById("chart-ws-status");
    const tradeJournalBody = document.querySelector(".table.table-dark-custom.table-sm tbody");

    const symbolUpper = symbol;
    const symbolLower = symbol.toLowerCase();

    const chartCtrl = new ChartController(container);
    chartCtrl.symbol = symbolUpper;
    chartCtrl.timeframe = timeframe || "1m";
    window.chartCtrl = chartCtrl;

    const layers = new LayerRenderer(chartCtrl.chart, chartCtrl.candles);
    layers.candlesData = chartCtrl.candlesData;
    chartCtrl.layerRenderer = layers;

    const ctx = {
        chatId,
        type,
        symbol: symbolUpper,
        exchange,
        network,
        timeframe: chartCtrl.timeframe,
        info: {}
    };

    let strategy;
    switch (type) {
        case "SCALPING":
        case "WINDOW_SCALPING":
            strategy = new ScalpingStrategy({ layers, ctx });
            break;
        case "FIBONACCI_GRID":
        case "FIBONACCI_RETRACE":
            strategy = new FibonacciStrategy({ layers, ctx });
            break;
        case "SMART_FUSION":
        case "HYBRID":
            strategy = new SmartFusionStrategy({ layers, ctx });
            break;
        case "EMA_CROSSOVER":
            strategy = new EmaStrategy({ layers, ctx });
            break;
        default:
            console.warn("⚠ Unknown strategy type, fallback to Generic:", type);
            strategy = new GenericStrategy({ layers, ctx });
            break;
    }

    console.log("🧠 Strategy initialized:", type, strategy?.constructor?.name);

    const layerCacheKey = `uiLayers:${chatId}:${type}:${exchange}:${network}:${symbolUpper}`;

    let _layerState = null;
    let _persistTimer = null;

    let socket = null;
    let stomp = null;
    let reconnectTimer = null;
    let reconnectAttempt = 0;
    let wsCount = 0;
    let lastLogAt = 0;
    let hadSuccessfulConnect = false;
    let initialSnapshotLoaded = false;

    const journalState = {
        limit: 100,
        rows: [],
        index: new Map()
    };

    function setWsStatus(online) {
        if (!wsStatusEl) return;
        wsStatusEl.textContent = online ? "ONLINE" : "OFFLINE";
        wsStatusEl.classList.toggle("text-success", !!online);
        wsStatusEl.classList.toggle("text-danger", !online);
    }

    function toTimeSec(v) {
        const n = Number(v);
        if (!Number.isFinite(n)) return NaN;
        return n > 10_000_000_000 ? Math.floor(n / 1000) : Math.floor(n);
    }

    function toTimeMs(v) {
        const n = Number(v);
        if (!Number.isFinite(n)) return Date.now();
        return n > 10_000_000_000 ? Math.floor(n) : Math.floor(n * 1000);
    }

    function formatDateTime(v) {
        const ms = toTimeMs(v);
        try {
            const d = new Date(ms);
            const yyyy = d.getFullYear();
            const mm = String(d.getMonth() + 1).padStart(2, "0");
            const dd = String(d.getDate()).padStart(2, "0");
            const hh = String(d.getHours()).padStart(2, "0");
            const mi = String(d.getMinutes()).padStart(2, "0");
            const ss = String(d.getSeconds()).padStart(2, "0");
            return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss}`;
        } catch (_) {
            return "—";
        }
    }

    function formatNumber(v, digits = 8) {
        const n = Number(v);
        if (!Number.isFinite(n)) return "—";

        const abs = Math.abs(n);
        let d = digits;
        if (abs >= 1000) d = 2;
        else if (abs >= 100) d = 3;
        else if (abs >= 1) d = 4;

        try {
            return n.toLocaleString("ru-RU", {
                minimumFractionDigits: 0,
                maximumFractionDigits: d
            });
        } catch (_) {
            return String(n);
        }
    }

    function formatSignedPnl(v) {
        const n = Number(v);
        if (!Number.isFinite(n)) return "—";
        const prefix = n > 0 ? "+" : "";
        const assetSuffix = pnlAsset ? ` ${pnlAsset}` : "";
        return `${prefix}${formatNumber(n, 8)}${assetSuffix}`;
    }

    function escapeHtml(v) {
        return String(v ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#039;");
    }

    function setJournalLoading(text = "Загрузка…") {
        if (!tradeJournalBody) return;
        tradeJournalBody.innerHTML = `<tr><td colspan="5" class="text-center text-secondary py-3">${escapeHtml(text)}</td></tr>`;
    }

    function detectQuoteAsset(sym) {
        const normalized = String(sym || "").trim().toUpperCase();
        for (const quote of ["USDT", "USDC", "FDUSD", "BUSD", "USDP", "DAI", "EUR", "TRY", "BTC", "ETH", "BNB"]) {
            if (normalized.endsWith(quote) && normalized.length > quote.length) {
                return quote;
            }
        }
        return "";
    }

    function normalizeSide(raw) {
        const side = String(raw ?? "").trim().toUpperCase();
        return side === "SELL" ? "SELL" : side === "BUY" ? "BUY" : "";
    }

    function normalizeSymbol(raw) {
        const normalized = String(raw ?? "").trim().toUpperCase().replaceAll("/", "");
        return normalized || null;
    }

    function finiteOrNull(value) {
        const n = Number(value);
        return Number.isFinite(n) ? n : null;
    }

    function roundTo(value, digits = 8) {
        const n = Number(value);
        if (!Number.isFinite(n)) return null;
        return Number(n.toFixed(digits));
    }

    function normalizeTradeRow(trade) {
        if (!trade || typeof trade !== "object") return null;

        const side = normalizeSide(trade.side ?? trade.type ?? trade.action);
        if (!side) return null;

        const tradeSymbol = normalizeSymbol(trade.symbol ?? trade.sym ?? trade.pair ?? symbolUpper);
        if (tradeSymbol && tradeSymbol !== symbolUpper) return null;

        const time = toTimeMs(trade.time ?? trade.timestamp ?? trade.ts ?? Date.now());
        const price = finiteOrNull(trade.price ?? trade.execPrice ?? trade.avgPrice ?? trade.entryPrice);
        const qty = finiteOrNull(trade.qty ?? trade.quantity ?? trade.execQty ?? trade.size ?? trade.executedQty);

        const rawPnl = trade.pnl ?? trade.profit ?? trade.realizedPnl ?? trade.realized_pnl ?? trade.realizedPnlUsd ?? trade.realized_pnl_usd;
        const pnl = finiteOrNull(rawPnl);
        const orderIdRaw = trade.orderId ?? trade.id ?? trade.order_id ?? trade.executionId ?? trade.execId ?? trade.tradeId ?? trade.trade_id ?? null;
        const orderId = orderIdRaw == null ? null : String(orderIdRaw).trim();

        const exactKey = orderId
            ? `id:${orderId}`
            : [side, tradeSymbol || symbolUpper, Math.floor(time / 1000), roundTo(price, 8) ?? "na", roundTo(qty, 12) ?? "na"].join("|");

        const fuzzyKey = [side, tradeSymbol || symbolUpper, Math.floor(time / 2000), roundTo(price, 8) ?? "na", roundTo(qty, 12) ?? "na"].join("|");

        return { exactKey, fuzzyKey, orderId, side, symbol: tradeSymbol || symbolUpper, time, price, qty, pnlRaw: pnl, pnl, computedPnl: null };
    }

    function shouldReplaceTrade(prev, next) {
        if (!prev) return true;
        if (!next) return false;

        const prevHasPnl = Number.isFinite(prev.pnlRaw);
        const nextHasPnl = Number.isFinite(next.pnlRaw);
        if (nextHasPnl && !prevHasPnl) return true;
        if (!nextHasPnl && prevHasPnl) return false;

        if (next.orderId && !prev.orderId) return true;
        if (!next.orderId && prev.orderId) return false;

        return next.time >= prev.time;
    }

    function upsertTradeRow(row) {
        if (!row) return;

        const byExact = journalState.index.get(row.exactKey);
        if (byExact) {
            if (shouldReplaceTrade(byExact, row)) {
                Object.assign(byExact, row);
            }
            return;
        }

        const candidate = journalState.rows.find(existing =>
            existing.fuzzyKey === row.fuzzyKey &&
            Math.abs(existing.time - row.time) <= 2000 &&
            existing.side === row.side &&
            roundTo(existing.price, 8) === roundTo(row.price, 8) &&
            roundTo(existing.qty, 12) === roundTo(row.qty, 12)
        );

        if (candidate) {
            if (shouldReplaceTrade(candidate, row)) {
                journalState.index.delete(candidate.exactKey);
                Object.assign(candidate, row);
                journalState.index.set(candidate.exactKey, candidate);
            }
            return;
        }

        journalState.rows.push(row);
        journalState.index.set(row.exactKey, row);
    }

    function calculateJournalPnl(rows) {
        const chronological = [...rows].sort((a, b) => a.time - b.time);
        const buyLots = [];

        for (const row of chronological) {
            row.computedPnl = null;
            row.pnl = Number.isFinite(row.pnlRaw) ? row.pnlRaw : null;

            if (!Number.isFinite(row.price) || !Number.isFinite(row.qty) || row.qty <= 0) continue;

            if (row.side === "BUY") {
                buyLots.push({ qtyLeft: row.qty, entryPrice: row.price });
                continue;
            }
            if (row.side !== "SELL") continue;

            let sellQtyLeft = row.qty;
            let computed = 0;

            while (sellQtyLeft > 0 && buyLots.length > 0) {
                const lot = buyLots[0];
                const matchedQty = Math.min(sellQtyLeft, lot.qtyLeft);
                if (!(matchedQty > 0)) {
                    buyLots.shift();
                    continue;
                }

                computed += (row.price - lot.entryPrice) * matchedQty;
                lot.qtyLeft -= matchedQty;
                sellQtyLeft -= matchedQty;

                if (!(lot.qtyLeft > 1e-12)) {
                    buyLots.shift();
                }
            }

            if (!Number.isFinite(row.pnl)) {
                row.computedPnl = computed;
                row.pnl = Number.isFinite(computed) ? computed : null;
            }
        }
    }

    function renderTradeJournal(rows) {
        if (!tradeJournalBody) return;

        if (!Array.isArray(rows) || rows.length === 0) {
            tradeJournalBody.innerHTML = `<tr><td colspan="5" class="text-center text-secondary py-3">Сделок пока нет</td></tr>`;
            return;
        }

        const html = rows.map(row => {
            const sideClass = row.side === "BUY" ? "text-success" : "text-danger";
            const pnlClass = row.pnl == null ? "text-secondary" : row.pnl >= 0 ? "text-success" : "text-danger";
            const pnlText = row.pnl == null ? (row.side === "BUY" ? "Открыта" : "—") : formatSignedPnl(row.pnl);

            return `
                <tr>
                    <td>${escapeHtml(formatDateTime(row.time))}</td>
                    <td class="${sideClass} fw-bold">${escapeHtml(row.side)}</td>
                    <td>${escapeHtml(formatNumber(row.price, 8))}</td>
                    <td>${escapeHtml(formatNumber(row.qty, 8))}</td>
                    <td class="${pnlClass}">${escapeHtml(pnlText)}</td>
                </tr>
            `;
        }).join("");

        tradeJournalBody.innerHTML = html;
    }

    function rebuildAndRenderJournal() {
        calculateJournalPnl(journalState.rows);
        journalState.rows = journalState.rows.sort((a, b) => b.time - a.time).slice(0, journalState.limit);
        journalState.index = new Map(journalState.rows.map(row => [row.exactKey, row]));
        renderTradeJournal(journalState.rows);
    }

    function setJournalRowsFromTrades(trades) {
        journalState.rows = [];
        journalState.index = new Map();
        for (const trade of Array.isArray(trades) ? trades : []) {
            const row = normalizeTradeRow(trade);
            if (!row) continue;
            upsertTradeRow(row);
        }
        rebuildAndRenderJournal();
    }

    function appendJournalTrade(trade) {
        const row = normalizeTradeRow(trade);
        if (!row) return;
        upsertTradeRow(row);
        rebuildAndRenderJournal();
    }

    function normalizeLayerState(raw) {
        if (raw == null || typeof raw !== "object") return null;

        const tpSl = (raw.tpSl && typeof raw.tpSl === "object") ? raw.tpSl : (raw.tp_sl && typeof raw.tp_sl === "object") ? raw.tp_sl : null;
        const windowZone = (raw.windowZone && typeof raw.windowZone === "object") ? raw.windowZone : (raw.window_zone && typeof raw.window_zone === "object") ? raw.window_zone : null;

        return {
            levels: Array.isArray(raw.levels) ? raw.levels : [],
            zone: raw.zone && typeof raw.zone === "object" ? raw.zone : null,
            tpSl,
            windowZone,
            priceLines: Array.isArray(raw.priceLines) ? raw.priceLines : Array.isArray(raw.price_lines) ? raw.price_lines : [],
            trades: Array.isArray(raw.trades) ? raw.trades : []
        };
    }

    function hasMeaningfulLayers(state) {
        if (!state) return false;
        return (
            (Array.isArray(state.levels) && state.levels.length > 0) ||
            !!state.zone || !!state.tpSl || !!state.windowZone ||
            (Array.isArray(state.priceLines) && state.priceLines.length > 0) ||
            (Array.isArray(state.trades) && state.trades.length > 0)
        );
    }

    function saveLayerStateToLocalStorage(state) {
        try { localStorage.setItem(layerCacheKey, JSON.stringify(state ?? {})); } catch (_) {}
    }

    function loadLayerStateFromLocalStorage() {
        try {
            const raw = localStorage.getItem(layerCacheKey);
            if (!raw) return null;
            return normalizeLayerState(JSON.parse(raw));
        } catch (_) {
            return null;
        }
    }

    function schedulePersistLayerState() {
        try { if (_persistTimer) clearTimeout(_persistTimer); } catch (_) {}
        _persistTimer = setTimeout(() => {
            _persistTimer = null;
            saveLayerStateToLocalStorage(_layerState);
        }, 150);
    }

    function clearAllVisualLayers() {
        try { layers.clearLevels?.(); } catch (_) {}
        try { layers.clearZone?.(); } catch (_) {}
        try { layers.clearTradeZone?.(); } catch (_) {}
        try { layers.clearTpSl?.(); } catch (_) {}
        try { layers.clearWindowZone?.(); } catch (_) {}
        try { layers.clearPriceLines?.(); } catch (_) {}
        try { layers.clearTrades?.(); } catch (_) {}

        try { strategy.onEvent?.({ type: "levels", levels: [] }); } catch (_) {}
        try { strategy.onEvent?.({ type: "zone", zone: null }); } catch (_) {}
        try { strategy.onEvent?.({ type: "trade_zone", tradeZone: null }); } catch (_) {}
        try { strategy.onEvent?.({ type: "tp_sl", tpSl: null }); } catch (_) {}
        try { strategy.onEvent?.({ type: "window_zone", windowZone: null }); } catch (_) {}
    }

    function applySnapshotLayerState(raw) {
        const state = normalizeLayerState(raw) || normalizeLayerState({});

        clearAllVisualLayers();

        if (Array.isArray(state.levels) && state.levels.length) {
            try { layers.renderLevels?.(state.levels); } catch (_) {}
        }
        if (state.zone) {
            try { layers.renderZone?.(state.zone); } catch (_) {}
        }
        if (state.tpSl) {
            try { layers.renderTpSl?.(state.tpSl); } catch (_) {
                try { strategy.onEvent?.({ type: "tp_sl", tpSl: state.tpSl }); } catch (_) {}
            }
        }
        if (state.windowZone) {
            try {
                layers.renderWindowZone?.({ ...state.windowZone, candlesData: Array.isArray(chartCtrl.candlesData) ? chartCtrl.candlesData : null });
            } catch (_) {
                try { strategy.onEvent?.({ type: "window_zone", windowZone: state.windowZone }); } catch (_) {}
            }
        }
        if (Array.isArray(state.priceLines)) {
            for (const pl of state.priceLines) {
                try { layers.renderPriceLine?.(pl); } catch (_) {}
            }
        }
        if (Array.isArray(state.trades)) {
            for (const tr of state.trades) {
                const timeSec = toTimeSec(tr?.time);
                if (!Number.isFinite(timeSec)) continue;
                try { layers.renderTrade?.(tr, timeSec); } catch (_) {}
            }
        }

        setJournalRowsFromTrades(state.trades || []);
    }

    function updateLayerStateFromEvent(ev) {
        if (!ev || typeof ev.type !== "string") return;
        const t = String(ev.type || "").trim();
        if (!t) return;
        if (!_layerState) _layerState = normalizeLayerState({});

        switch (t) {
            case "levels":
                _layerState.levels = Array.isArray(ev.levels) ? ev.levels : [];
                schedulePersistLayerState();
                break;
            case "zone":
                _layerState.zone = ev.zone ?? null;
                schedulePersistLayerState();
                break;
            case "tp_sl":
                _layerState.tpSl = ev.tpSl ?? null;
                schedulePersistLayerState();
                break;
            case "window_zone":
                _layerState.windowZone = ev.windowZone ?? null;
                schedulePersistLayerState();
                break;
            case "price_line": {
                const pl = ev.priceLine || ev;
                if (!_layerState.priceLines) _layerState.priceLines = [];
                if (!pl?.name || pl?.price == null) {
                    _layerState.priceLines = [];
                } else {
                    const name = String(pl.name).toUpperCase();
                    _layerState.priceLines = (_layerState.priceLines || []).filter(x => String(x?.name || "").toUpperCase() !== name);
                    _layerState.priceLines.push(pl);
                }
                schedulePersistLayerState();
                break;
            }
            case "trade": {
                const tr = ev.trade || ev;
                if (!_layerState.trades) _layerState.trades = [];
                _layerState.trades.push({ ...tr, time: ev.time ?? tr?.time ?? Date.now() });
                if (_layerState.trades.length > 300) {
                    _layerState.trades = _layerState.trades.slice(-300);
                }
                schedulePersistLayerState();
                appendJournalTrade(tr);
                break;
            }
            default:
                break;
        }
    }

    function eventSymbolUpper(ev) {
        const s = ev?.symbol ?? ev?.sym ?? ev?.trade?.symbol ?? ev?.order?.symbol ?? ev?.priceLine?.symbol ?? null;
        if (s == null) return null;
        const normalized = String(s).trim().toUpperCase().replaceAll("/", "");
        return normalized || null;
    }

    function isMarketEvent(ev) {
        const t = String(ev?.type || "").trim().toLowerCase();
        return t === "price" || t === "candle" || t === "kline";
    }

    function isCandleEvent(ev) {
        const t = String(ev?.type || "").trim().toLowerCase();
        return t === "candle" || t === "kline";
    }

    function wsDedupMeta(ev) {
        const t = String(ev?.type || "");
        const time = String(ev?.time ?? ev?.timestamp ?? ev?.trade?.time ?? "");
        const sym = String(eventSymbolUpper(ev) || "");
        const id = String(ev?.id ?? ev?.orderId ?? ev?.tradeId ?? ev?.executionId ?? ev?.trade?.id ?? ev?.trade?.orderId ?? ev?.trade?.tradeId ?? "");
        return `${t}|${sym}|${time}|${id}|${JSON.stringify(ev?.trade || ev?.priceLine || ev?.zone || {})}`;
    }

    const wsSeenMap = new Map();

    function wsSeenRecently(meta) {
        const now = Date.now();
        const prev = wsSeenMap.get(meta);

        for (const [k, v] of wsSeenMap.entries()) {
            if (now - v > 3000) wsSeenMap.delete(k);
        }
        if (prev && now - prev < 1500) return true;
        wsSeenMap.set(meta, now);
        return false;
    }

    const bootLayers = loadLayerStateFromLocalStorage();
    if (bootLayers && hasMeaningfulLayers(bootLayers)) {
        _layerState = bootLayers;
        applySnapshotLayerState(bootLayers);
    } else {
        renderTradeJournal([]);
    }

    setJournalLoading("Загрузка…");

    const snapshotUrl = `/api/chart/strategy?chatId=${encodeURIComponent(chatId)}&type=${encodeURIComponent(type)}&symbol=${encodeURIComponent(symbolUpper)}&timeframe=${encodeURIComponent(chartCtrl.timeframe || timeframe || "1m")}`;

    function applySnapshot(data) {
        if (data?.timeframe) {
            const tf = String(data.timeframe).trim().toLowerCase();
            if (tf) {
                chartCtrl.timeframe = tf;
                ctx.timeframe = tf;
            }
        }

        if (data?.info && typeof data.info === "object") {
            ctx.info = data.info;
            try { strategy.setInfo?.(data.info); } catch (_) {}
        }

        if (Array.isArray(data?.candles)) {
            chartCtrl.setHistory(data.candles);
            layers.candlesData = chartCtrl.candlesData;
            try { strategy.onCandleHistory?.(chartCtrl.candlesData); } catch (_) {}
        }

        const incomingLayers = normalizeLayerState(data?.layers);
        if (incomingLayers) {
            _layerState = incomingLayers;
            applySnapshotLayerState(incomingLayers);
            schedulePersistLayerState();
        } else if (!hasMeaningfulLayers(_layerState)) {
            renderTradeJournal([]);
        }

        chartCtrl.adjustBarSpacing();
        initialSnapshotLoaded = true;
    }

    fetch(snapshotUrl)
        .then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            return r.json();
        })
        .then(data => applySnapshot(data))
        .catch(err => {
            console.error("❌ Snapshot load failed", err);
            if (!hasMeaningfulLayers(_layerState)) renderTradeJournal([]);
        });

    const destinations = [
        `/topic/strategy/${chatId}/${type}`,
        `/topic/strategy/${chatId}/${type}/${symbolUpper}`,
        `/topic/strategy/${chatId}/${type}/${symbolLower}`
    ];

    function cleanupWs() {
        try { if (reconnectTimer) clearTimeout(reconnectTimer); } catch (_) {}
        reconnectTimer = null;

        try { if (stomp && stomp.connected) stomp.disconnect(() => {}); } catch (_) {}
        stomp = null;

        try { if (socket) socket.close(); } catch (_) {}
        socket = null;

        setWsStatus(false);
    }

    function scheduleReconnect(reason) {
        cleanupWs();
        reconnectAttempt = Math.min(10, reconnectAttempt + 1);
        const delay = Math.min(15_000, 500 * Math.pow(2, reconnectAttempt - 1));
        console.warn(`⚠ WS reconnect scheduled in ${delay}ms (attempt=${reconnectAttempt}) reason=${reason}`);
        reconnectTimer = setTimeout(() => connectWs(true), delay);
    }

    function shouldAcceptIncomingLayers(nextState) {
        const nextHas = hasMeaningfulLayers(nextState);
        const currentHas = hasMeaningfulLayers(_layerState);
        if (nextHas) return true;
        if (!currentHas) return true;
        console.warn("⏭ empty layers ignored because current state already has data");
        return false;
    }

    function requestReplay() {
        fetch(`/api/strategy/${chatId}/${type}/replay?symbol=${encodeURIComponent(symbolUpper)}`, { method: "POST" }).catch(() => {});
    }

    function connectWs(isReconnect = false) {
        cleanupWs();

        if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
            console.warn("⚠ SockJS/Stomp not loaded, WS disabled");
            return;
        }

        socket = new SockJS("/ws/strategy/");
        stomp = Stomp.over(socket);
        stomp.debug = null;

        socket.onclose = () => scheduleReconnect("socket_close");
        socket.onerror = () => scheduleReconnect("socket_error");

        stomp.connect(
            {},
            () => {
                reconnectAttempt = 0;
                setWsStatus(true);
                console.log("✅ STOMP CONNECTED");

                destinations.forEach(dest => {
                    stomp.subscribe(dest, msg => {
                        let ev;
                        try { ev = JSON.parse(msg.body); } catch { return; }

                        const meta = wsDedupMeta(ev);
                        if (wsSeenRecently(meta)) return;

                        wsCount++;
                        const now = Date.now();
                        if (now - lastLogAt > 3000) {
                            lastLogAt = now;
                            console.log(`📡 WS IN (#${wsCount}) from ${dest}:`, msg.body?.slice(0, 220));
                        }

                        const evSym = eventSymbolUpper(ev);
                        if (evSym && evSym !== symbolUpper) return;

                        if (ev?.type === "layers") {
                            const nextState = normalizeLayerState(ev.layers) || normalizeLayerState({});
                            if (shouldAcceptIncomingLayers(nextState)) {
                                _layerState = nextState;
                                applySnapshotLayerState(_layerState);
                                schedulePersistLayerState();
                            }
                            return;
                        }

                        if (isMarketEvent(ev)) {
                            chartCtrl.onWsMessage(ev);
                            layers.candlesData = chartCtrl.candlesData;
                        }

                        try { strategy.onEvent?.(ev); } catch (e) { console.warn("⚠ strategy.onEvent failed", e); }

                        updateLayerStateFromEvent(ev);

                        if (isCandleEvent(ev)) {
                            try { strategy.onCandleHistory?.(chartCtrl.candlesData); } catch (_) {}
                        }
                    });

                    console.log("✅ SUBSCRIBED", dest);
                });

                if (hadSuccessfulConnect || isReconnect || !initialSnapshotLoaded) {
                    requestReplay();
                }
                hadSuccessfulConnect = true;
            },
            err => {
                console.warn("❌ STOMP CONNECT ERROR", err);
                scheduleReconnect("stomp_connect_error");
            }
        );
    }

    connectWs(false);

    window.addEventListener("resize", () => {
        try {
            chartCtrl.chart.applyOptions({ width: container.clientWidth });
            chartCtrl.adjustBarSpacing();
        } catch (_) {}
    });

    window.addEventListener("beforeunload", () => {
        try { if (_persistTimer) clearTimeout(_persistTimer); } catch (_) {}
        cleanupWs();
    });

    chartCtrl.adjustBarSpacing();
    console.log("📊 Strategy Dashboard INITIALIZED");
});
