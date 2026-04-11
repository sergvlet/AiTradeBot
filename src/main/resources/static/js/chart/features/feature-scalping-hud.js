"use strict";

export class FeatureScalpingHud {
    constructor({ layers, defaults = {} } = {}) {
        this.layers = layers;
        this.defaults = defaults;
    }

    onEvent(ev) {
        if (!ev || !this.layers) return;
        // Заглушка без шума: HUD можно расширять дальше без поломки дашборда.
    }
}
