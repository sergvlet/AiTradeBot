"use strict";

import { FeatureBase } from "./feature-base.js";

export class FeatureScalpingSeries extends FeatureBase {
    constructor({ layers, ctx } = {}) {
        super({ layers, ctx });
    }

    onEvent(ev) {
        if (!ev) return;

        if (ev.type === "ema_series" && ev.emaSeries) {
            this.callLayer("renderEmaSeries", ev.emaSeries);
            return;
        }

        if (ev.type === "series_bundle" && ev.seriesBundle) {
            this.callLayer("renderSeriesBundle", ev.seriesBundle);
        }
    }

    clear() {
        this.callLayer("clearEmaSeries");
        this.callLayer("clearSeriesBundle");
    }
}
