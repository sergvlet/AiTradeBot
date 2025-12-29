(function () {

    // =====================================================================
    // ROOT / CONTEXT
    // =====================================================================

    const root = document.querySelector('.strategy-settings-page');
    if (!root) return;

    const exchange = root.getAttribute('data-exchange');
    const network  = root.getAttribute('data-network');

    function getAccountAsset() {
        const el = document.querySelector('[name="accountAsset"]');
        return el && el.value ? el.value : 'USDT';
    }

    const ul     = document.getElementById('symbolList');
    const hidden = document.getElementById('symbolHidden');
    const label  = document.getElementById('symbolLabel');
    const dropdownToggle = document.getElementById('symbolDropdown');

    if (!ul || !hidden || !label || !dropdownToggle) return;

    // =====================================================================
    // EXCHANGE LIMIT FIELDS
    // =====================================================================

    const exMinNotional = document.getElementById('exMinNotional');
    const exStepSize    = document.getElementById('exStepSize');
    const exTickSize    = document.getElementById('exTickSize');
    const exMaxOrders   = document.getElementById('exMaxOrders');

    const exMinNotionalScope = document.getElementById('exMinNotionalScope');
    const exStepSizeScope    = document.getElementById('exStepSizeScope');
    const exTickSizeScope    = document.getElementById('exTickSizeScope');
    const exMaxOrdersScope   = document.getElementById('exMaxOrdersScope');

    let currentMode = 'POPULAR';
    const symbolMap = new Map();

    // =====================================================================
    // LOAD SYMBOLS
    // =====================================================================

    async function load(mode = currentMode) {

        currentMode = mode;
        symbolMap.clear();

        ul.innerHTML = `<li class="dropdown-item text-muted small">Загрузка…</li>`;

        const qs = new URLSearchParams({
            exchange,
            network,
            accountAsset: getAccountAsset(),
            mode
        });

        try {
            const res = await fetch(`/api/market/symbols?${qs.toString()}`);
            if (!res.ok) throw new Error('HTTP ' + res.status);

            const list = await res.json();
            list.forEach(s => symbolMap.set(s.symbol, s));

            render(list);

            // если символ уже выбран — применяем сразу
            const currentSymbol = hidden.value;
            if (currentSymbol && symbolMap.has(currentSymbol)) {
                applySymbol(symbolMap.get(currentSymbol));
            }

        } catch (e) {
            console.error('Symbol load failed', e);
            ul.innerHTML =
                `<li class="dropdown-item text-danger small">Ошибка загрузки пар</li>`;
        }
    }

    // =====================================================================
    // RENDER LIST (LAZY)
    // =====================================================================

    function render(list) {

        ul.innerHTML = '';
        ul.style.maxHeight = '360px';
        ul.style.overflowY = 'auto';

        let index = 0;
        const BATCH = 100;

        function renderBatch() {
            const slice = list.slice(index, index + BATCH);

            slice.forEach(s => {
                const li = document.createElement('li');
                li.innerHTML = `
                    <a class="dropdown-item d-flex justify-content-between align-items-center" href="#">
                        <span><b>${s.baseAsset}</b> / ${s.quoteAsset}</span>
                        <span class="text-muted small">${fmtPct(s.priceChangePct24h)}</span>
                    </a>
                `;

                li.addEventListener('click', e => {
                    e.preventDefault();
                    applySymbol(s);
                    dropdownToggle.click();
                });

                ul.appendChild(li);
            });

            index += BATCH;
        }

        renderBatch();

        ul.onscroll = () => {
            if (ul.scrollTop + ul.clientHeight >= ul.scrollHeight - 20) {
                if (index < list.length) renderBatch();
            }
        };

        const info = document.createElement('li');
        info.className = 'dropdown-item text-muted small text-center';
        info.textContent = `Всего пар: ${list.length}`;
        ul.appendChild(info);
    }

    // =====================================================================
    // APPLY SYMBOL + EXCHANGE LIMITS
    // =====================================================================

    function applySymbol(s) {

        hidden.value = s.symbol;
        label.textContent = `${s.baseAsset} / ${s.quoteAsset}`;

        applyScoped(exMinNotional, exMinNotionalScope, s.minNotional, s.minNotionalScope);
        applyScoped(exStepSize,    exStepSizeScope,    s.stepSize,    s.stepSizeScope);
        applyScoped(exTickSize,    exTickSizeScope,    s.tickSize,    s.tickSizeScope);
        applyScoped(exMaxOrders,   exMaxOrdersScope,   s.maxOrders,   s.maxOrdersScope);
    }

    function applyScoped(input, badge, value, scope) {

        if (input) {
            input.value =
                value === null || value === undefined || value === ''
                    ? '—'
                    : String(value);
        }

        setScopeBadge(badge, scope || 'UNKNOWN');
    }

    function setScopeBadge(el, scope) {
        if (!el) return;

        el.className = 'badge';

        switch (scope) {
            case 'SYMBOL':
                el.textContent = '🧩 SYMBOL';
                el.classList.add('bg-info');
                break;
            case 'ACCOUNT':
                el.textContent = '👤 ACCOUNT';
                el.classList.add('bg-warning');
                break;
            default:
                el.textContent = '❓ UNKNOWN';
                el.classList.add('bg-secondary');
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    function fmtPct(v) {
        if (v === null || v === undefined) return '';
        const n = Number(v);
        if (Number.isNaN(n)) return '';
        return (n >= 0 ? '+' : '') + n.toFixed(2) + '%';
    }

    // =====================================================================
    // MODE SWITCH
    // =====================================================================

    document.querySelectorAll('[data-symbol-mode]').forEach(btn => {
        btn.addEventListener('click', () => {

            document
                .querySelectorAll('[data-symbol-mode]')
                .forEach(b => b.classList.remove('active'));

            btn.classList.add('active');
            load(btn.getAttribute('data-symbol-mode'));
        });
    });

    // =====================================================================
    // INIT
    // =====================================================================

    load('POPULAR');

})();
