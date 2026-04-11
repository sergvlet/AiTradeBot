"use strict";

export class FeatureScalpingSeries {
    constructor({ layers, ctx } = {}) {
        this.layers = layers;
        this.ctx = ctx;
    }

    onEvent(_) {}
    onCandleHistory(_) {}
}
