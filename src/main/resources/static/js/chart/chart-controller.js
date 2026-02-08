"use strict";

/**
 * ChartController (FIX v2)
 * =======================
 * Что исправлено:
 * 1) ✅ _candlesByTime всегда инициализирован
 * 2) ✅ время нормализуется: ms -> sec, потом bucket по timeframe
 * 3) ✅ upsertCandle НЕ использует _lastCandle как prev для другого времени (это ломало свечи)
 * 4) ✅ WS partial-merge: если пришёл только close/high/low - корректно достраиваем
 * 5) ✅ candlesData всегда синхронизирован с map
 */

export class ChartController {
    constructor(container) {
        this.container = container;
        if (!container) {
            console.error("❌ ChartController: container is null");
            return;
        }

        const { clientWidth, clientHeight } = container;
        const LightweightCharts = window.LightweightCharts;

        this.chart = LightweightCharts.createChart(container, {
            width: clientWidth || 800,
            height: clientHeight || 420,
            layout: {
                background: { color: "#0e0f11" },
                textColor: "#e0e0e0"
            },
            grid: {
                vertLines: { visible: false },
                horzLines: { visible: false }
            },
            rightPriceScale: { borderColor: "#444" },
            timeScale: {
                borderColor: "#444",
                timeVisible: true,
                secondsVisible: false
            }
        });

        this.candles = this.chart.addCandlestickSeries({
            upColor: "#26a69a",
            downColor: "#ef5350",
            wickUpColor: "#26a69a",
            wickDownColor: "#ef5350",
            borderVisible: false
        });

        // ===========================================
        // state
        // ===========================================
        this.symbol = null;
        this.timeframe = "1m";

        this.candlesData = [];
        this._candlesByTime = new Map();
        this._lastCandle = null;

        // log throttling
        this._lastWarnAt = 0;
    }

    // =====================================================
    // Time helpers
    // =====================================================

    timeframeToSeconds(tf) {
        const s = String(tf || "1m").trim().toLowerCase();
        const m = s.match(/^(\d+)\s*([smhd])$/);
        if (!m) return 60;
        const n = parseInt(m[1], 10);
        const u = m[2];
        if (u === "s") return n;
        if (u === "m") return n * 60;
        if (u === "h") return n * 60 * 60;
        if (u === "d") return n * 60 * 60 * 24;
        return 60;
    }

    /**
     * rawTime может прийти в:
     *  - ms (1770501120000)
     *  - sec (1770501120)
     *  - ISO string
     */
    getTimeSeconds(rawTime) {
        if (rawTime === null || rawTime === undefined) return null;

        if (typeof rawTime === "number") {
            // ms -> sec (в Binance/в твоём WINDOW_SCALPING часто ms)
            if (rawTime > 4_000_000_000) return Math.floor(rawTime / 1000);
            return Math.floor(rawTime);
        }

        if (typeof rawTime === "string") {
            const t = rawTime.trim();
            if (!t) return null;

            if (/^\d+$/.test(t)) {
                const n = Number(t);
                if (!Number.isFinite(n)) return null;
                return this.getTimeSeconds(n);
            }

            const ms = Date.parse(t);
            if (Number.isFinite(ms)) return Math.floor(ms / 1000);
        }

        return null;
    }

    /**
     * Приводим любое время к "бакету" таймфрейма.
     * Пример: tf=1m => time всегда кратен 60 сек.
     */
    normalizeTimeToBucket(rawTime) {
        const sec = this.getTimeSeconds(rawTime);
        if (sec === null) return null;

        const bucket = this.timeframeToSeconds(this.timeframe);
        return Math.floor(sec / bucket) * bucket;
    }

    // =====================================================
    // Candle parsing
    // =====================================================

    parseNum(v) {
        if (v === null || v === undefined) return null;
        if (typeof v === "number") return Number.isFinite(v) ? v : null;
        const s = String(v).trim();
        if (!s) return null;
        const n = Number(s);
        return Number.isFinite(n) ? n : null;
    }

    /**
     * Поддерживаем разные форматы:
     * - REST: {time, open, high, low, close}
     * - WS:  ev.kline / ev.k / ev.data.k
     * - Binance: k.o/k.h/k.l/k.c и t/T
     */
    extractCandle(ev) {
        if (!ev) return null;

        // REST уже может прислать candle напрямую
        if (ev.time !== undefined && (ev.open !== undefined || ev.close !== undefined)) {
            return {
                time: ev.time,
                open: ev.open,
                high: ev.high,
                low: ev.low,
                close: ev.close
            };
        }

        const k = ev.kline || ev.k || ev?.data?.k || null;
        if (!k) return null;

        // ВАЖНО: приоритет OPEN TIME (начало свечи), а не closeTime.
        // Для бинанса: t = open time, T = close time.
        const rawTime =
            k.openTime ?? k.open_time ?? k.t ??
            k.startTime ?? k.start_time ??
            ev.time ?? ev.t ?? ev.T ??
            null;

        const o = (k.open ?? k.o);
        const h = (k.high ?? k.h);
        const l = (k.low  ?? k.l);
        const c = (k.close ?? k.c);

        return { time: rawTime, open: o, high: h, low: l, close: c };
    }

    // =====================================================
    // History
    // =====================================================

    setHistory(candles) {
        if (!Array.isArray(candles)) return;

        const map = new Map();

        for (const c of candles) {
            const t = this.normalizeTimeToBucket(c?.time ?? c?.t ?? c?.T);
            if (t === null) continue;

            const o  = this.parseNum(c.open  ?? c.o);
            const h  = this.parseNum(c.high  ?? c.h);
            const l  = this.parseNum(c.low   ?? c.l);
            const cl = this.parseNum(c.close ?? c.c);

            if (o === null || h === null || l === null || cl === null) continue;

            // защита от мусора
            const hi = Math.max(h, o, cl);
            const lo = Math.min(l, o, cl);

            map.set(t, { time: t, open: o, high: hi, low: lo, close: cl });
        }

        const times = Array.from(map.keys()).sort((a, b) => a - b);
        const out = times.map(t => map.get(t));

        this.candlesData = out;
        this._candlesByTime = map;
        this._lastCandle = out.length ? out[out.length - 1] : null;

        this.candles.setData(out);

        console.log("📦 History loaded", out.length);
    }

    // =====================================================
    // Upsert (WS)
    // =====================================================

    /**
     * Вставка/обновление свечи.
     * КРИТИЧНО: prev берём ТОЛЬКО по тому же bucket-time.
     * НЕЛЬЗЯ подмешивать _lastCandle другого времени — это делает "рваные" свечи.
     */
    upsertCandle(partial) {
        const t = this.normalizeTimeToBucket(partial?.time);
        if (t === null) return;

        const prev = this._candlesByTime.get(t) || null;

        let o = this.parseNum(partial.open);
        let h = this.parseNum(partial.high);
        let l = this.parseNum(partial.low);
        let c = this.parseNum(partial.close);

        // 1) Если prev есть — достраиваем из prev
        if (prev) {
            if (o === null) o = prev.open;
            if (c === null) c = prev.close;
            if (h === null) h = prev.high;
            if (l === null) l = prev.low;
        }

        // 2) Если prev нет — минимальные правила, чтобы не рисовать мусор
        //    Если прилетел только close — считаем это "тик" внутри свечи и делаем open=close
        if (!prev) {
            if (o === null && c !== null) o = c;
            if (c === null && o !== null) c = o;
        }

        // если всё ещё не хватает базовых значений — пропускаем
        if (o === null || c === null) return;

        // high/low если не пришли — строим из o/c
        if (h === null) h = Math.max(o, c);
        if (l === null) l = Math.min(o, c);

        // защита от перевёрнутых значений
        h = Math.max(h, o, c);
        l = Math.min(l, o, c);

        const candle = { time: t, open: o, high: h, low: l, close: c };

        const existed = this._candlesByTime.has(t);
        this._candlesByTime.set(t, candle);

        // _lastCandle обновляем только если это реально самая новая свеча
        if (!this._lastCandle || t >= this._lastCandle.time) {
            this._lastCandle = candle;
        }

        // sync candlesData
        if (!this.candlesData || this.candlesData.length === 0) {
            this.candlesData = [candle];
        } else {
            const last = this.candlesData[this.candlesData.length - 1];

            if (last.time === t) {
                this.candlesData[this.candlesData.length - 1] = candle;
            } else if (last.time < t) {
                this.candlesData.push(candle);
            } else {
                // insert/replace in the middle (редко, но бывает из-за дублей топиков)
                let lo = 0, hi = this.candlesData.length - 1, idx = -1;
                while (lo <= hi) {
                    const mid = (lo + hi) >> 1;
                    const mt = this.candlesData[mid].time;
                    if (mt === t) { idx = mid; break; }
                    if (mt < t) lo = mid + 1;
                    else hi = mid - 1;
                }
                if (idx >= 0) this.candlesData[idx] = candle;
                else this.candlesData.splice(lo, 0, candle);
            }
        }

        // render
        this.candles.update(candle);

        // spacing иногда полезно, но не спамим
        if (!existed) this.adjustBarSpacing();
    }

    // =====================================================
    // WS entry
    // =====================================================

    onWsMessage(ev) {
        if (!ev) return;

        const candleEv =
            ev?.type === "candle" ||
            !!ev?.kline || !!ev?.k || !!ev?.data?.k;

        if (!candleEv) return;

        const c = this.extractCandle(ev);
        if (!c) return;

        this.upsertCandle(c);
    }

    // =====================================================
    // UX
    // =====================================================

    adjustBarSpacing() {
        try {
            const len = this.candlesData?.length || 0;
            if (len < 50) return;

            const bs = Math.max(2, Math.min(10, Math.floor(1200 / Math.sqrt(len))));
            this.chart.timeScale().applyOptions({ barSpacing: bs });
        } catch (e) {
            const now = Date.now();
            if (now - this._lastWarnAt > 3000) {
                this._lastWarnAt = now;
                console.warn("⚠ adjustBarSpacing failed", e);
            }
        }
    }
}
