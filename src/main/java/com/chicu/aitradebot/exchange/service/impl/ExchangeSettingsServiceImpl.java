package com.chicu.aitradebot.exchange.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.repository.ExchangeSettingsRepository;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeSettingsServiceImpl implements ExchangeSettingsService {

    private final ExchangeSettingsRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    // ========================================================================
    // GET OR CREATE (таб NETWORK)
    // ========================================================================
    @Override
    @Transactional
    public ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network) {

        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .orElseGet(() -> {
                    ExchangeSettings s = new ExchangeSettings();
                    s.setChatId(chatId);
                    s.setExchange(exchange.toUpperCase());
                    s.setNetwork(network);

                    // ❗ ключи ПУСТЫЕ, но НЕ null
                    s.setApiKey("");
                    s.setApiSecret("");
                    s.setPassphrase("");
                    s.setSubAccount("");
                    s.setEnabled(false);

                    s.setCreatedAt(Instant.now());
                    s.setUpdatedAt(Instant.now());

                    repository.save(s);

                    log.info("🆕 Созданы ExchangeSettings {}@{} (chatId={})",
                            exchange, network, chatId);

                    return s;
                });
    }

    // ========================================================================
    // SAVE NETWORK (биржа + сеть) — ❗ НЕ ТРОГАЕТ КЛЮЧИ
    // ========================================================================
    @Transactional
    public ExchangeSettings saveNetwork(Long chatId, String exchange, NetworkType network) {

        ExchangeSettings s = getOrCreate(chatId, exchange, network);

        // ничего кроме сети / биржи не меняем
        s.setExchange(exchange.toUpperCase());
        s.setNetwork(network);
        s.setUpdatedAt(Instant.now());

        repository.save(s);

        log.info("🌐 Network updated {}@{} (chatId={})",
                exchange, network, chatId);

        return s;
    }

    // ========================================================================
    // SAVE KEYS — ❗ НИКОГДА НЕ ЗАТИРАЕТ СУЩЕСТВУЮЩИЕ
    // ========================================================================
    @Transactional
    public ExchangeSettings saveKeys(
            Long chatId,
            String exchange,
            NetworkType network,
            String apiKey,
            String apiSecret,
            String passphrase
    ) {

        ExchangeSettings s = getOrCreate(chatId, exchange, network);

        // 🔐 ТОЛЬКО если пришли НЕ пустые
        if (!isBlank(apiKey)) {
            s.setApiKey(apiKey.trim());
        }

        if (!isBlank(apiSecret)) {
            s.setApiSecret(apiSecret.trim());
        }

        if (!isBlank(passphrase)) {
            s.setPassphrase(passphrase.trim());
        }

        s.setEnabled(true);
        s.setUpdatedAt(Instant.now());

        repository.save(s);

        log.info("🔐 API keys updated {}@{} (chatId={})",
                exchange, network, chatId);

        return s;
    }

    // ========================================================================
    // FIND
    // ========================================================================
    @Override
    public List<ExchangeSettings> findAllByChatId(Long chatId) {
        return repository.findAllByChatId(chatId);
    }

    // ========================================================================
    // DELETE
    // ========================================================================
    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .ifPresent(repository::delete);

        log.warn("🗑 Deleted ExchangeSettings {}@{} (chatId={})",
                exchange, network, chatId);
    }

    @Override
    @Transactional
    public ExchangeSettings save(ExchangeSettings settings) {
        settings.setUpdatedAt(Instant.now());
        return repository.save(settings);
    }


    // ========================================================================
    // DIAGNOSE (AJAX)
    // ========================================================================
    @Override
    public ApiKeyDiagnostics diagnose(Long chatId, String exchange, NetworkType network) {

        ExchangeSettings s =
                repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                        .orElse(null);

        return testConnectionDetailed(s);
    }

    // ========================================================================
    // БЫСТРАЯ ПРОВЕРКА (true/false)
    // ========================================================================
    @Override
    public boolean testConnection(ExchangeSettings s) {

        if (s == null || isBlank(s.getExchange()) || s.getNetwork() == null) {
            return false;
        }

        if (isBlank(s.getApiKey()) || isBlank(s.getApiSecret())) {
            return false;
        }

        return switch (s.getExchange().toUpperCase()) {
            case "BINANCE" -> testBinanceConnectionQuick(s);
            case "BYBIT"   -> testBybitConnectionQuick(s);
            default -> false;
        };
    }

    @Override
    public ApiKeyDiagnostics testConnectionDetailed(ExchangeSettings s) {

        if (s == null) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("UNKNOWN")
                    .message("Exchange settings not found")
                    .build();
        }

        String exchange = s.getExchange().toUpperCase();

        if (isBlank(s.getApiKey()) || isBlank(s.getApiSecret())) {
            return ApiKeyDiagnostics.notConfigured(
                    exchange,
                    "API key or secret is empty"
            );
        }

        return switch (exchange) {
            case "BINANCE" -> diagnoseBinance(s);
            case "BYBIT"   -> diagnoseBybit(s);
            default -> ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(exchange)
                    .message("Unsupported exchange: " + exchange)
                    .build();
        };
    }


    // ========================================================================
    // QUICK TESTS
    // ========================================================================
    private boolean testBinanceConnectionQuick(ExchangeSettings s) {
        try {
            String base = (s.getNetwork() == NetworkType.TESTNET)
                    ? "https://testnet.binance.vision"
                    : "https://api.binance.com";

            long ts = System.currentTimeMillis();
            String query = "recvWindow=5000&timestamp=" + ts;
            String sign = hmacSha256(query, s.getApiSecret());

            HttpHeaders h = new HttpHeaders();
            h.set("X-MBX-APIKEY", s.getApiKey());

            ResponseEntity<String> r = restTemplate.exchange(
                    base + "/api/v3/account?" + query + "&signature=" + sign,
                    HttpMethod.GET,
                    new HttpEntity<>("", h),
                    String.class
            );

            return r.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            return false;
        }
    }

    private boolean testBybitConnectionQuick(ExchangeSettings s) {
        try {
            String base;

            if (s.getNetwork() == NetworkType.TESTNET) {
                // ⚠️ TESTNET для BYBIT = DEMO
                base = "https://api-demo.bybit.com";
            } else {
                base = "https://api.bybit.com";
            }

            long ts = System.currentTimeMillis();
            String recv = "5000";
            String query = "accountType=UNIFIED";

            String sign = hmacSha256(
                    ts + s.getApiKey() + recv + query,
                    s.getApiSecret()
            );

            HttpHeaders h = new HttpHeaders();
            h.set("X-BAPI-API-KEY", s.getApiKey());
            h.set("X-BAPI-SIGN", sign);
            h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            h.set("X-BAPI-RECV-WINDOW", recv);

            ResponseEntity<String> r = restTemplate.exchange(
                    base + "/v5/account/wallet-balance?" + query,
                    HttpMethod.GET,
                    new HttpEntity<>("", h),
                    String.class
            );

            return r.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            log.warn("Bybit quick test failed", e);
            return false;
        }
    }

    // ========================================================================
    // helpers
    // ========================================================================
    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 error", e);
        }
    }
    private ApiKeyDiagnostics diagnoseBinance(ExchangeSettings s) {

        String base = (s.getNetwork() == NetworkType.TESTNET)
                ? "https://testnet.binance.vision"
                : "https://api.binance.com";

        try {
            long ts = System.currentTimeMillis();
            String query = "recvWindow=5000&timestamp=" + ts;
            String sign = hmacSha256(query, s.getApiSecret());

            HttpHeaders h = new HttpHeaders();
            h.set("X-MBX-APIKEY", s.getApiKey());

            ResponseEntity<String> r = restTemplate.exchange(
                    base + "/api/v3/account?" + query + "&signature=" + sign,
                    HttpMethod.GET,
                    new HttpEntity<>("", h),
                    String.class
            );

            JSONObject json = new JSONObject(r.getBody());

            return ApiKeyDiagnostics.builder()
                    .ok(true)
                    .exchange("BINANCE")
                    .message("Binance API OK")
                    .apiKeyValid(true)
                    .secretValid(true)
                    .signatureValid(true)
                    .accountReadable(true)
                    .tradingAllowed(true)
                    .ipAllowed(true)      // запрос прошёл → IP разрешён
                    .networkOk(true)
                    .extra(Map.of(
                            "balances", json.optJSONArray("balances") != null
                    ))
                    .build();

        } catch (HttpClientErrorException e) {

            int status = e.getStatusCode().value();

            // 401 / 403 — почти всегда IP whitelist или ключ
            boolean ipBlocked = status == 401 || status == 403;

            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BINANCE")
                    .message("Binance API error: " + status)
                    .apiKeyValid(true)
                    .secretValid(true)
                    .signatureValid(true)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(!ipBlocked)   // ❌ если 401/403 — IP не разрешён
                    .networkOk(true)
                    .build();

        } catch (Exception e) {

            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BINANCE")
                    .message("Binance connection failed: " + e.getMessage())
                    .networkOk(false)
                    .build();
        }
    }


    private ApiKeyDiagnostics diagnoseBybit(ExchangeSettings s) {

        String base = (s.getNetwork() == NetworkType.TESTNET)
                ? "https://api-demo.bybit.com"
                : "https://api.bybit.com";

        try {
            long ts = System.currentTimeMillis();
            String recv = "5000";
            String query = "accountType=UNIFIED";

            String sign = hmacSha256(
                    ts + s.getApiKey() + recv + query,
                    s.getApiSecret()
            );

            HttpHeaders h = new HttpHeaders();
            h.set("X-BAPI-API-KEY", s.getApiKey());
            h.set("X-BAPI-SIGN", sign);
            h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            h.set("X-BAPI-RECV-WINDOW", recv);

            ResponseEntity<String> r = restTemplate.exchange(
                    base + "/v5/account/wallet-balance?" + query,
                    HttpMethod.GET,
                    new HttpEntity<>("", h),
                    String.class
            );

            JSONObject json = new JSONObject(r.getBody());
            int retCode = json.optInt("retCode", -1);

            boolean ok = retCode == 0;

            return ApiKeyDiagnostics.builder()
                    .ok(ok)
                    .exchange("BYBIT")
                    .message(ok ? "Bybit API OK" : "Bybit error: " + retCode)
                    .apiKeyValid(ok)
                    .secretValid(ok)
                    .signatureValid(ok)
                    .accountReadable(ok)
                    .tradingAllowed(ok)
                    // 🔥 ВАЖНО
                    .ipAllowed(true)          // если запрос прошёл — IP разрешён
                    .networkOk(true)
                    .extra(Map.of(
                            "retCode", retCode,
                            "retMsg", json.optString("retMsg")
                    ))
                    .build();

        } catch (HttpClientErrorException e) {

            // 👇 Реальный признак IP whitelist
            boolean ipBlocked =
                    e.getStatusCode().value() == 401 ||
                    e.getStatusCode().value() == 403;

            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BYBIT")
                    .message("Bybit API error: " + e.getStatusCode())
                    .apiKeyValid(true)
                    .secretValid(true)
                    .signatureValid(true)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(!ipBlocked)   // ❌ если 401/403 — IP не разрешён
                    .networkOk(true)
                    .build();

        } catch (Exception e) {

            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BYBIT")
                    .message("Bybit connection failed: " + e.getMessage())
                    .networkOk(false)
                    .build();
        }
    }


    public static ApiKeyDiagnostics notConfigured(String exchange, String message) {
        return ApiKeyDiagnostics.builder()
                .ok(false)
                .exchange(exchange)
                .message(message)
                .apiKeyValid(false)
                .secretValid(false)
                .signatureValid(false)
                .accountReadable(false)
                .tradingAllowed(false)
                .ipAllowed(false)
                .networkOk(true)
                .build();
    }

}
