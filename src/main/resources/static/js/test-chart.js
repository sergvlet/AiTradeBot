// /static/js/test-chart.js

(function () {
    const chartContainer = document.getElementById('chart-container');
    if (!chartContainer) {
        console.error('❌ Не найден #chart-container');
        return;
    }

    // =========================
    // 1. Инициализация графика
    // =========================
    const chart = LightweightCharts.createChart(chartContainer, {
        layout: {
            background: { color: '#0e0f11' },
            textColor: '#d0d0d0',
        },
        grid: {
            vertLines: { color: 'rgba(197, 203, 206, 0.15)' },
            horzLines: { color: 'rgba(197, 203, 206, 0.15)' },
        },
        crosshair: {
            mode: LightweightCharts.CrosshairMode.Normal,
        },
        rightPriceScale: {
            borderColor: 'rgba(197, 203, 206, 0.4)',
        },
        timeScale: {
            borderColor: 'rgba(197, 203, 206, 0.4)',
            timeVisible: true,
            secondsVisible: false,
        },
    });

    const candleSeries = chart.addCandlestickSeries({
        upColor: '#4caf50',
        downColor: '#f44336',
        borderVisible: false,
        wickUpColor: '#4caf50',
        wickDownColor: '#f44336',
    });

    const emaFastSeries = chart.addLineSeries({
        lineWidth: 1,
        title: 'EMA Fast',
    });

    const emaSlowSeries = chart.addLineSeries({
        lineWidth: 1,
        title: 'EMA Slow',
    });

    // =========================
    // 2. Помощники
    // =========================

    function msToSeconds(ms) {
        return Math.floor(ms / 1000);
    }

    function formatDateTime(tsMs) {
        const d = new Date(tsMs);
        return d.toLocaleString();
    }

    function buildUrl() {
        const chatId = Number(document.getElementById('chat-id-input').value || '1');
        const symbol = (document.getElementById('symbol-input').value || 'BTCUSDT').trim();
        const tf = document.getElementById('tf-select').value || '15m';

        // Обновляем бейджи
        document.getElementById('symbol-badge').innerText = symbol;
        document.getElementById('tf-badge').innerText = tf;

        const params = new URLSearchParams({
            chatId: chatId.toString(),
            symbol: symbol,
            timeframe: tf,
        });

        return '/api/test/chart?' + params.toString();
    }

    function renderTradesList(trades) {
        const container = document.getElementById('trades-list');
        container.innerHTML = '';

        if (!trades || trades.length === 0) {
            container.innerHTML = '<span class="text-muted">Нет сделок</span>';
            return;
        }

        trades
            .slice()
            .sort((a, b) => a.timestamp - b.timestamp)
            .forEach(t => {
                const sideClass = t.side === 'BUY' ? 'badge-buy' : 'badge-sell';
                const pnlColor = t.realizedPnlUsd > 0 ? 'text-success' :
                    (t.realizedPnlUsd < 0 ? 'text-danger' : 'text-muted');

                const div = document.createElement('div');
                div.className = 'mb-2';

                div.innerHTML = `
                    <div>
                        <span class="badge ${sideClass} me-1">${t.side}</span>
                        <strong>${t.symbol}</strong>
                        <span class="text-muted small ms-1">${formatDateTime(t.timestamp)}</span>
                    </div>
                    <div class="small">
                        Цена: <span class="text-warning">${t.price}</span>,
                        объём: ${t.quantity}
                    </div>
                    <div class="small">
                        TP: ${t.takeProfitPrice ?? '-'},
                        SL: ${t.stopLossPrice ?? '-'}
                    </div>
                    <div class="small ${pnlColor}">
                        PnL: ${t.realizedPnlUsd != null ? t.realizedPnlUsd.toFixed(4) + ' USDT' : '-'}
                        (${t.realizedPnlPct != null ? t.realizedPnlPct.toFixed(4) + ' %' : '-'})
                    </div>
                    <hr class="my-1 border-secondary" />
                `;
                container.appendChild(div);
            });
    }

    function buildMarkersFromTrades(trades) {
        if (!trades) return [];

        return trades.map(t => {
            const isBuy = (t.side || '').toUpperCase() === 'BUY';
            return {
                time: msToSeconds(t.timestamp),
                position: isBuy ? 'belowBar' : 'aboveBar',
                color: isBuy ? '#4caf50' : '#f44336',
                shape: isBuy ? 'arrowUp' : 'arrowDown',
                text: `${t.side} ${t.price}`,
            };
        });
    }

    // =========================
    // 3. Загрузка и отрисовка
    // =========================

    async function loadChart() {
        try {
            const url = buildUrl();
            console.log('🔍 Запрос графика:', url);

            const resp = await axios.get(url);
            const data = resp.data;

            console.log('✅ Ответ /api/test/chart:', data);

            const candles = (data.candles || []).map(c => ({
                time: msToSeconds(c.time),
                open: c.open,
                high: c.high,
                low: c.low,
                close: c.close,
            }));

            const emaFast = (data.emaFast || []).map(p => ({
                time: msToSeconds(p.time),
                value: p.value,
            }));

            const emaSlow = (data.emaSlow || []).map(p => ({
                time: msToSeconds(p.time),
                value: p.value,
            }));

            const trades = data.trades || [];

            candleSeries.setData(candles);
            emaFastSeries.setData(emaFast);
            emaSlowSeries.setData(emaSlow);

            const markers = buildMarkersFromTrades(trades);
            candleSeries.setMarkers(markers);

            chart.timeScale().fitContent();

            renderTradesList(trades);

        } catch (e) {
            console.error('❌ Ошибка загрузки графика:', e);
            alert('Ошибка при запросе /api/test/chart. Смотри консоль и логи сервера.');
        }
    }

    // =========================
    // 4. Обработчики кнопок
    // =========================

    document.getElementById('apply-btn').addEventListener('click', () => {
        loadChart();
    });

    document.getElementById('reload-btn').addEventListener('click', () => {
        loadChart();
    });

    // Авто-загрузка при открытии страницы
    loadChart();

})();
