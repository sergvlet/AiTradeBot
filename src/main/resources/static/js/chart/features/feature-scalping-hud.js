"use strict";

import { FeatureBase } from "./feature-base.js";

export class FeatureScalpingHud extends FeatureBase {
    constructor({ layers, defaults } = {}) {
        super({ layers });
        this.defaults = defaults || {};
        this.root = null;
        this.summaryEl = null;
        this.toggleBtn = null;
        this.bodyEl = null;
        this.collapsed = true;
        this.last = { ...this.defaults };
    }

    onEvent(ev) {
        if (!ev) return;

        if (ev.type === "signal" && ev.signal?.reason && String(ev.signal.reason).startsWith("SCALPING_STATS|")) {
            this.last = { ...this.last, ...this.#parseReason(String(ev.signal.reason)) };
            this.#render();
            return;
        }

        if (ev.type === "atr" && ev.atr) {
            const atr = Number(ev.atr.atr);
            const vol = Number(ev.atr.volatilityPct);

            if (Number.isFinite(atr)) this.last.atrPct = atr;
            if (Number.isFinite(vol)) this.last.windowRange = vol;

            this.#render();
        }
    }

    clear() {
        if (this.root) {
            this.root.remove();
            this.root = null;
            this.summaryEl = null;
            this.toggleBtn = null;
            this.bodyEl = null;
        }
    }

    #parseReason(reason) {
        const out = {};
        const parts = reason.split("|").slice(1);

        for (const part of parts) {
            const idx = part.indexOf("=");
            if (idx <= 0) continue;

            const key = part.slice(0, idx).trim();
            const raw = part.slice(idx + 1).trim();
            const num = Number(raw.replace(",", "."));

            out[key] = Number.isFinite(num) ? num : raw;
        }

        return out;
    }

    #findChartHost() {
        return document.querySelector(".strategy-dashboard__chart")
            || document.querySelector(".tv-lightweight-charts")?.parentElement
            || document.querySelector(".chart-container")
            || document.querySelector("[data-role='strategy-chart']");
    }

    #findHeaderHost(chartHost) {
        if (!chartHost) return null;

        const card = chartHost.closest(".strategy-dashboard__card, .strategy-dashboard__panel, .card, .panel, .widget")
            || chartHost.parentElement;

        if (!card) return null;

        return card.querySelector(
            ".strategy-dashboard__chart-header, .strategy-dashboard__header, .card-header, .panel-header, .widget-header"
        );
    }

    #ensureOverlayParent(node) {
        if (!node) return;
        const pos = window.getComputedStyle(node).position;
        if (pos === "static" || !pos) {
            node.style.position = "relative";
        }
        if (!node.style.zIndex) {
            node.style.zIndex = "1";
        }
    }

    #ensureRoot() {
        if (this.root && document.body.contains(this.root)) {
            return this.root;
        }

        const chartHost = this.#findChartHost();
        const headerHost = this.#findHeaderHost(chartHost);

        const existing = document.querySelector(".scalping-hud-compact");
        if (existing) {
            this.root = existing;
            this.summaryEl = existing.querySelector(".scalping-hud-compact__summary");
            this.toggleBtn = existing.querySelector(".scalping-hud-compact__toggle");
            this.bodyEl = existing.querySelector(".scalping-hud-compact__body");
            return existing;
        }

        const root = document.createElement("div");
        root.className = "scalping-hud-compact";

        const bar = document.createElement("div");
        bar.className = "scalping-hud-compact__bar";
        bar.style.display = "flex";
        bar.style.alignItems = "center";
        bar.style.gap = "8px";
        bar.style.minWidth = "0";
        bar.style.maxWidth = "100%";
        bar.style.padding = "4px 8px";
        bar.style.borderRadius = "10px";
        bar.style.border = "1px solid rgba(148,163,184,0.18)";
        bar.style.background = "rgba(15,23,42,0.88)";
        bar.style.boxShadow = "0 4px 14px rgba(2,6,23,0.18)";
        bar.style.cursor = "pointer";

        const badge = document.createElement("div");
        badge.textContent = "SC";
        badge.style.flexShrink = "0";
        badge.style.fontSize = "10px";
        badge.style.fontWeight = "700";
        badge.style.letterSpacing = ".06em";
        badge.style.color = "#cbd5e1";
        badge.style.background = "rgba(51,65,85,0.95)";
        badge.style.border = "1px solid rgba(148,163,184,0.16)";
        badge.style.borderRadius = "999px";
        badge.style.padding = "2px 6px";

        const summary = document.createElement("div");
        summary.className = "scalping-hud-compact__summary";
        summary.style.minWidth = "0";
        summary.style.flex = "1";
        summary.style.font = "11px/1.2 system-ui, -apple-system, Segoe UI, Roboto, sans-serif";
        summary.style.color = "#e2e8f0";
        summary.style.whiteSpace = "nowrap";
        summary.style.overflow = "hidden";
        summary.style.textOverflow = "ellipsis";

        const toggle = document.createElement("button");
        toggle.type = "button";
        toggle.className = "scalping-hud-compact__toggle";
        toggle.style.flexShrink = "0";
        toggle.style.border = "0";
        toggle.style.background = "transparent";
        toggle.style.color = "#94a3b8";
        toggle.style.fontSize = "12px";
        toggle.style.cursor = "pointer";
        toggle.style.padding = "0";
        toggle.style.lineHeight = "1";
        toggle.textContent = "▾";

        const body = document.createElement("div");
        body.className = "scalping-hud-compact__body";
        body.style.display = "none";
        body.style.position = "absolute";
        body.style.top = "calc(100% + 6px)";
        body.style.right = "0";
        body.style.width = "min(360px, calc(100vw - 32px))";
        body.style.padding = "10px 12px";
        body.style.borderRadius = "12px";
        body.style.border = "1px solid rgba(148,163,184,0.18)";
        body.style.background = "rgba(15,23,42,0.96)";
        body.style.boxShadow = "0 10px 30px rgba(2,6,23,0.35)";
        body.style.zIndex = "40";

        bar.appendChild(badge);
        bar.appendChild(summary);
        bar.appendChild(toggle);
        root.appendChild(bar);
        root.appendChild(body);

        const toggleCollapsed = (e) => {
            if (e) {
                e.preventDefault();
                e.stopPropagation();
            }
            this.collapsed = !this.collapsed;
            this.#applyCollapsedState();
        };

        bar.addEventListener("click", toggleCollapsed);
        toggle.addEventListener("click", toggleCollapsed);

        if (headerHost) {
            headerHost.style.display = headerHost.style.display || "flex";
            headerHost.style.alignItems = headerHost.style.alignItems || "center";
            headerHost.style.gap = headerHost.style.gap || "8px";
            headerHost.style.flexWrap = headerHost.style.flexWrap || "wrap";
            this.#ensureOverlayParent(headerHost);

            root.style.position = "relative";
            root.style.marginLeft = "auto";
            root.style.zIndex = "35";
            root.style.maxWidth = "min(520px, 100%)";

            headerHost.appendChild(root);
        } else {
            const host = chartHost || document.body;
            this.#ensureOverlayParent(host);

            root.style.position = "absolute";
            root.style.top = "8px";
            root.style.right = "8px";
            root.style.zIndex = "35";
            root.style.maxWidth = "min(520px, calc(100% - 16px))";

            host.appendChild(root);
        }

        this.root = root;
        this.summaryEl = summary;
        this.toggleBtn = toggle;
        this.bodyEl = body;

        this.#applyCollapsedState();
        return root;
    }

    #applyCollapsedState() {
        if (!this.bodyEl || !this.toggleBtn) return;

        this.bodyEl.style.display = this.collapsed ? "none" : "block";
        this.toggleBtn.textContent = this.collapsed ? "▾" : "▴";
        this.toggleBtn.title = this.collapsed ? "Развернуть" : "Свернуть";
    }

    #fmt(v, digits = 4) {
        const n = Number(v);
        if (!Number.isFinite(n)) return "—";
        return n.toFixed(digits).replace(/\.?0+$/, "");
    }

    #summaryText() {
        const score = this.#fmt(this.last.score, 2);
        const block = String(this.last.block ?? "—");
        const rsi = this.#fmt(this.last.rsi ?? this.last.rsiFilter, 2);
        return `Score ${score} • Block ${block} • RSI ${rsi}`;
    }

    #toneForBlock(block) {
        return block === "READY" ? "#22c55e" : "#f59e0b";
    }

    #row(label, value, tone = "#cbd5e1") {
        return `
            <div style="display:flex;justify-content:space-between;gap:10px;">
                <span style="color:#94a3b8;">${label}</span>
                <span style="color:${tone};font-weight:600;">${value}</span>
            </div>
        `;
    }

    #render() {
        this.#ensureRoot();
        if (!this.root || !this.summaryEl || !this.bodyEl) return;

        const block = String(this.last.block ?? "—");
        const blockTone = this.#toneForBlock(block);

        this.summaryEl.textContent = this.#summaryText();

        this.bodyEl.innerHTML = `
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px 14px;font:11px/1.3 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;color:#e2e8f0;">
                ${this.#row("Impulse %", this.#fmt(this.last.impulse ?? this.last.minImpulsePct), "#e2e8f0")}
                ${this.#row("EMA diff %", this.#fmt(this.last.emaDiff ?? this.last.emaDiffThreshold), "#f59e0b")}
                ${this.#row("Volume ratio", this.#fmt(this.last.volumeRatio), "#38bdf8")}
                ${this.#row("Spread %", this.#fmt(this.last.spreadPct ?? this.last.spreadLimitPct), "#f87171")}
                ${this.#row("ATR %", this.#fmt(this.last.atrPct), "#a78bfa")}
                ${this.#row("Window range %", this.#fmt(this.last.windowRange), "#cbd5e1")}
                ${this.#row("From low %", this.#fmt(this.last.fromLow), "#22c55e")}
                ${this.#row("From high %", this.#fmt(this.last.fromHigh), "#f97316")}
                ${this.#row("RSI", this.#fmt(this.last.rsi ?? this.last.rsiFilter, 2), "#facc15")}
                ${this.#row("R/R", this.#fmt(this.last.rr ?? this.last.riskRewardMin, 2), "#4ade80")}
                ${this.#row("Score", this.#fmt(this.last.score, 2), "#e879f9")}
                ${this.#row("Block", block, blockTone)}
            </div>
        `;

        this.#applyCollapsedState();
    }
}