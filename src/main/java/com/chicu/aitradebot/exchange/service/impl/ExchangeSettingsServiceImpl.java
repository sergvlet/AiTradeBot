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
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeSettingsServiceImpl implements ExchangeSettingsService {

    private final ExchangeSettingsRepository repository;

    // Лучше бы инжектить как @Bean, но оставляю как у тебя (минимум изменений)
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    @Transactional
    public ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network) {
        String ex = normalizeExchange(exchange);

        return repository.findByChatIdAndExchangeAndNetwork(chatId, ex, network)
                .orElseGet(() -> {
                    ExchangeSettings s = ExchangeSettings.builder()
                            .chatId(chatId)
                            .exchange(ex)
                            .network(network)
                            // ключи могут быть null (после миграции)
                            .apiKey(null)
                            .apiSecret(null)
                            .passphrase(null)
                            .subAccount(null)
                            .build();

                    ExchangeSettings saved = repository.save(s);
                    log.info("🆕 Созданы ExchangeSettings {}@{} (chatId={})", ex, network, chatId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public ExchangeSettings saveKeys(Long chatId,
                                     String exchange,
                                     NetworkType network,
                                     String apiKey,
                                     String apiSecret,
                                     String passphrase,
                                     String subAccount) {

        String ex = normalizeExchange(exchange);

        // ✅ одна строка на (chatId, exchange, network) — дальше только UPDATE
        ExchangeSettings s = getOrCreate(chatId, ex, network);

        // ✅ ВАЖНО: пустая строка должна ОЧИЩАТЬ ключ (становится null)
        s.setApiKey(normalizeNullable(apiKey));
        s.setApiSecret(normalizeNullable(apiSecret));
        s.setPassphrase(normalizeNullable(passphrase));
        s.setSubAccount(normalizeNullable(subAccount));

        ExchangeSettings saved = repository.save(s);

        // лог без спама и без секретов
        log.info("🔐 Keys saved {}@{} (chatId={}, hasBaseKeys={}, hasAnySecret={})",
                ex, network, chatId, saved.hasBaseKeys(), saved.hasAnySecret());

        return saved;
    }

    @Override
    public List<ExchangeSettings> findAllByChatId(Long chatId) {
        return repository.findAllByChatId(chatId);
    }

    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.deleteByChatIdAndExchangeAndNetwork(chatId, normalizeExchange(exchange), network);
        log.warn("🗑 Deleted ExchangeSettings {}@{} (chatId={})", exchange, network, chatId);
    }

    @Override
    public ApiKeyDiagnostics diagnose(Long chatId, String exchange, NetworkType network) {
        ExchangeSettings s = repository.findByChatIdAndExchangeAndNetwork(chatId, normalizeExchange(exchange), network)
                .orElse(null);
        return testConnectionDetailed(s);
    }

    @Override
    public boolean testConnection(ExchangeSettings s) {
        if (s == null) return false;
        if (isBlank(s.getExchange()) || s.getNetwork() == null) return false;
        if (!s.hasBaseKeys()) return false;

        return switch (normalizeExchange(s.getExchange())) {
            case "BINANCE" -> testBinanceConnectionQuick(s);
            case "BYBIT" -> testBybitConnectionQuick(s);
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

        String exchange = normalizeExchange(s.getExchange());

        // ✅ если ключей нет — диагностику не делаем
        if (!s.hasBaseKeys()) {
            return ApiKeyDiagnostics.notConfigured(exchange, "Ключи не заданы (apiKey/apiSecret пустые)");
        }

        return switch (exchange) {
            case "BINANCE" -> diagnoseBinance(s);
            case "BYBIT" -> diagnoseBybit(s);
            default -> ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(exchange)
                    .message("Диагностика не поддерживается для: " + exchange)
                    .build();
        };
    }

    // =====================================================================
    // QUICK TESTS
    // =====================================================================

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
            String base = (s.getNetwork() == NetworkType.TESTNET)
                    ? "https://api-demo.bybit.com"
                    : "https://api.bybit.com";

            long ts = System.currentTimeMillis();
            String recv = "5000";
            String query = "accountType=UNIFIED";

            String sign = hmacSha256(ts + s.getApiKey() + recv + query, s.getApiSecret());

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
            log.warn("Bybit quick test failed: {}", e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // DIAGNOSE
    // =====================================================================

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
                    .ipAllowed(true)
                    .networkOk(true)
                    .extra(Map.of("balancesPresent", json.optJSONArray("balances") != null))
                    .build();

        } catch (HttpClientErrorException e) {

            int status = e.getStatusCode().value();
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
                    .ipAllowed(!ipBlocked)
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

            String sign = hmacSha256(ts + s.getApiKey() + recv + query, s.getApiSecret());

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
                    .ipAllowed(true)
                    .networkOk(true)
                    .extra(Map.of(
                            "retCode", retCode,
                            "retMsg", json.optString("retMsg")
                    ))
                    .build();

        } catch (HttpClientErrorException e) {

            boolean ipBlocked = e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403;

            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BYBIT")
                    .message("Bybit API error: " + e.getStatusCode().value())
                    .apiKeyValid(true)
                    .secretValid(true)
                    .signatureValid(true)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(!ipBlocked)
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

    // =====================================================================
    // HELPERS
    // =====================================================================

    private String normalizeExchange(String exchange) {
        return (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String normalizeNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
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
}

