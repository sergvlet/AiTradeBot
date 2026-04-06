
package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.WebChartFacade;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyReplayApiController {

    private static final long REPLAY_DEDUP_WINDOW_MS = 1500L;

    private final SimpMessagingTemplate ws;
    private final StrategySettingsService settingsService;
    private final WebChartFacade chartFacade;

    private final ConcurrentMap<ReplayKey, ReplayState> replayState = new ConcurrentHashMap<>();

    @PostMapping("/{chatId}/{type}/replay")
    public ResponseEntity<Void> replay(@PathVariable("chatId") long chatId,
                                       @PathVariable("type") StrategyType type,
                                       @RequestParam(name = "symbol", required = false) String symbolOverride) {

        log.info("🔁 [WEB] replay request: chatId={}, type={}", chatId, type);
        if (chatId <= 0 || type == null) {
            return ResponseEntity.ok().build();
        }

        StrategySettings settings;
        try {
            settings = settingsService.getOrCreate(chatId, type);
        } catch (Exception e) {
            log.warn("⚠ [WEB] replay: cannot load settings chatId={} type={} err={}", chatId, type, e.getMessage());
            return ResponseEntity.ok().build();
        }

        final String symbol = firstNonBlankSymbol(
                symbolOverride,
                settings != null ? settings.getSymbol() : null
        );
        if (symbol == null) {
            log.warn("⚠ [WEB] replay skipped: symbol missing chatId={} type={}", chatId, type);
            return ResponseEntity.ok().build();
        }

        final String timeframe = normalizeOptional(settings != null ? settings.getTimeframe() : null);
        final String exchange = normalizeExchange(settings != null ? settings.getExchangeName() : null);
        final String network = settings != null && settings.getNetworkType() != null
                ? settings.getNetworkType().name()
                : null;
        final int limit = normalizeLimit(settings != null ? settings.getCachedCandlesLimit() : null);

        StrategyChartDto snapshot;
        try {
            snapshot = chartFacade.buildChart(chatId, type, symbol, timeframe, limit);
        } catch (Exception e) {
            log.warn("⚠ [WEB] replay: chart snapshot failed chatId={} type={} symbol={} err={}",
                    chatId, type, symbol, e.toString());
            snapshot = StrategyChartDto.builder()
                    .candles(java.util.List.of())
                    .layers(StrategyChartDto.Layers.empty())
                    .info(java.util.Map.of())
                    .build();
        }

        StrategyChartDto.Layers layers = snapshot != null && snapshot.getLayers() != null
                ? snapshot.getLayers()
                : StrategyChartDto.Layers.empty();

        int trades = layers.getTrades() != null ? layers.getTrades().size() : 0;
        int levels = layers.getLevels() != null ? layers.getLevels().size() : 0;
        int priceLines = layers.getPriceLines() != null ? layers.getPriceLines().size() : 0;
        boolean empty = isEmpty(layers);
        String fingerprint = buildFingerprint(exchange, network, symbol, timeframe, snapshot, trades, levels, priceLines, empty);

        ReplayKey replayKey = new ReplayKey(chatId, type, symbol);
        long nowMs = System.currentTimeMillis();
        ReplayState prevState = replayState.get(replayKey);
        if (prevState != null
                && Objects.equals(prevState.fingerprint(), fingerprint)
                && nowMs - prevState.sentAtMs() < REPLAY_DEDUP_WINDOW_MS) {
            log.info("🔁 [WEB] replay dedup skip chatId={} type={} ex={} net={} symbol={} tf={} fingerprint={} ageMs={}",
                    chatId,
                    type,
                    exchange,
                    network,
                    symbol,
                    timeframe,
                    fingerprint,
                    nowMs - prevState.sentAtMs());
            return ResponseEntity.ok().build();
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "layers");
        msg.put("chatId", chatId);
        msg.put("strategyType", type.name());
        msg.put("symbol", symbol);
        msg.put("exchange", exchange);
        msg.put("network", network);
        msg.put("timeframe", timeframe);
        msg.put("time", Instant.now().toEpochMilli());
        msg.put("lastPrice", snapshot != null ? snapshot.getLastPrice() : null);
        msg.put("info", snapshot != null && snapshot.getInfo() != null ? snapshot.getInfo() : Map.of());
        msg.put("layers", layers);

        String baseDest = "/topic/strategy/" + chatId + "/" + type.name();
        ws.convertAndSend(baseDest, msg);
        ws.convertAndSend(baseDest + "/" + symbol, msg);
        ws.convertAndSend(baseDest + "/" + symbol.toLowerCase(Locale.ROOT), msg);

        replayState.put(replayKey, new ReplayState(fingerprint, nowMs));

        log.info("🔁 [WEB] replay sent chatId={} type={} ex={} net={} symbol={} tf={} trades={} levels={} priceLines={} empty={} fingerprint={}",
                chatId,
                type,
                exchange,
                network,
                symbol,
                timeframe,
                trades,
                levels,
                priceLines,
                empty,
                fingerprint);

        return ResponseEntity.ok().build();
    }

    private boolean isEmpty(StrategyChartDto.Layers layers) {
        return (layers.getLevels() == null || layers.getLevels().isEmpty())
                && layers.getZone() == null
                && layers.getTpSl() == null
                && layers.getWindowZone() == null
                && (layers.getPriceLines() == null || layers.getPriceLines().isEmpty())
                && (layers.getTrades() == null || layers.getTrades().isEmpty());
    }

    private static int normalizeLimit(Integer v) {
        if (v == null) return 0;
        if (v < 10 || v > 1500) return 0;
        return v;
    }

    private static String normalizeOptional(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.equals("default") || s.equals("<default>")) return null;
        return s;
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) {
            return null;
        }
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT).replace("/", "");
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlankSymbol(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normSymbol(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String buildFingerprint(String exchange,
                                           String network,
                                           String symbol,
                                           String timeframe,
                                           StrategyChartDto snapshot,
                                           int trades,
                                           int levels,
                                           int priceLines,
                                           boolean empty) {
        String lastPrice = snapshot != null && snapshot.getLastPrice() != null
                ? String.format(Locale.ROOT, "%.8f", snapshot.getLastPrice())
                : "null";
        int candles = snapshot != null && snapshot.getCandles() != null ? snapshot.getCandles().size() : 0;
        return String.join("|",
                String.valueOf(exchange),
                String.valueOf(network),
                String.valueOf(symbol),
                String.valueOf(timeframe),
                String.valueOf(candles),
                String.valueOf(trades),
                String.valueOf(levels),
                String.valueOf(priceLines),
                String.valueOf(empty),
                lastPrice);
    }

    private record ReplayKey(long chatId, StrategyType type, String symbol) {}

    private record ReplayState(String fingerprint, long sentAtMs) {}
}
