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

@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeSettingsServiceImpl implements ExchangeSettingsService {

    private final ExchangeSettingsRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    // ========================================================================
    // getOrCreate
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
    // save
    // ========================================================================
    @Override
    @Transactional
    public ExchangeSettings save(ExchangeSettings incoming) {

        Optional<ExchangeSettings> existingOpt =
                repository.findByChatIdAndExchangeAndNetwork(
                        incoming.getChatId(),
                        incoming.getExchange(),
                        incoming.getNetwork()
                );

        ExchangeSettings target = existingOpt.orElseGet(ExchangeSettings::new);

        target.setChatId(incoming.getChatId());
        target.setExchange(incoming.getExchange().toUpperCase());
        target.setNetwork(incoming.getNetwork());
        target.setApiKey(incoming.getApiKey());
        target.setApiSecret(incoming.getApiSecret());
        target.setPassphrase(incoming.getPassphrase());
        target.setSubAccount(incoming.getSubAccount());
        target.setEnabled(incoming.isEnabled());

        if (target.getCreatedAt() == null) {
            target.setCreatedAt(Instant.now());
        }

        target.setUpdatedAt(Instant.now());

        ExchangeSettings saved = repository.save(target);

        log.info("💾 ExchangeSettings updated {}@{} (chatId={})",
                saved.getExchange(), saved.getNetwork(), saved.getChatId());

        return saved;
    }

    // ========================================================================
    // find-all
    // ========================================================================
    @Override
    public List<ExchangeSettings> findAllByChatId(Long chatId) {
        return repository.findAllByChatId(chatId);
    }

    // ========================================================================
    // delete
    // ========================================================================
    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .ifPresent(repository::delete);

        log.warn("🗑 Deleted ExchangeSettings {}@{} (chatId={})",
                exchange, network, chatId);
    }

    // ========================================================================
    // testConnection — быстрый (true/false)
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

    // ========================================================================
    // Binance simple test (/api/v3/account)
    // ========================================================================
    private boolean testBinanceConnectionQuick(ExchangeSettings s) {

        String baseUrl = (s.getNetwork() == NetworkType.TESTNET)
                ? "https://testnet.binance.vision"
                : "https://api.binance.com";

        long ts = System.currentTimeMillis();
        String query = "recvWindow=5000&timestamp=" + ts;
        String signature = hmacSha256(query, s.getApiSecret());

        String url = baseUrl + "/api/v3/account?" + query + "&signature=" + signature;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", s.getApiKey());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>("", headers), String.class);

            return resp.getStatusCode().is2xxSuccessful();

        } catch (HttpClientErrorException e) {
            log.warn("❌ testBinanceConnectionQuick error: {}", e.getResponseBodyAsString());
            return false;
        }
    }

    // ========================================================================
    // Bybit simple test (/v5/account/wallet-balance)
    // ========================================================================
    private boolean testBybitConnectionQuick(ExchangeSettings s) {
        String baseUrl = (s.getNetwork() == NetworkType.TESTNET)
                ? "https://api-testnet.bybit.com"
                : "https://api.bybit.com";

        long ts = System.currentTimeMillis();
        String recvWindow = "5000";
        String query = "accountType=UNIFIED";

        // ts + apiKey + recvWindow + query
        String preSign = ts + s.getApiKey() + recvWindow + query;
        String signature = hmacSha256(preSign, s.getApiSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-BAPI-API-KEY", s.getApiKey());
        headers.set("X-BAPI-SIGN", signature);
        headers.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
        headers.set("X-BAPI-RECV-WINDOW", recvWindow);

        String url = baseUrl + "/v5/account/wallet-balance?" + query;

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>("", headers), String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                return false;
            }

            JSONObject json = new JSONObject(resp.getBody());
            return json.optInt("retCode", -1) == 0;

        } catch (Exception e) {
            log.warn("❌ testBybitConnectionQuick error: {}", e.getMessage());
            return false;
        }
    }

    // ========================================================================
// УНИВЕРСАЛЬНАЯ ДЕТАЛЬНАЯ ДИАГНОСТИКА (Binance + Bybit)
// ========================================================================
    @Override
    public ApiKeyDiagnostics testConnectionDetailed(ExchangeSettings s) {

        // 1) Вообще нет настроек
        if (s == null) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(null)
                    .message("Настройки биржи отсутствуют. Сначала сохраните API Key и Secret.")
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(false)
                    .build();
        }

        String ex = s.getExchange();

        // 2) Биржа не указана
        if (isBlank(ex)) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(null)
                    .message("Вы не выбрали биржу. Укажите Binance или Bybit.")
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(false)
                    .build();
        }

        String exchangeUpper = ex.trim().toUpperCase();

        // 3) Разбор по биржам
        return switch (exchangeUpper) {
            case "BINANCE" -> testConnectionDetailedBinance(s);
            case "BYBIT"   -> testConnectionDetailedBybit(s);
            default -> ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange(exchangeUpper)
                    .message("Диагностика поддерживается только для Binance и Bybit.")
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(false)
                    .extra(Map.of("exchange", exchangeUpper))
                    .build();
        };
    }

    // ========================================================================
// Детальная диагностика BINANCE
// ========================================================================
    private ApiKeyDiagnostics testConnectionDetailedBinance(ExchangeSettings s) {

        ApiKeyDiagnostics.ApiKeyDiagnosticsBuilder out =
                ApiKeyDiagnostics.builder().exchange("BINANCE");

        // ---- 0. Проверка ключей ----
        if (isBlank(s.getApiKey()) || isBlank(s.getApiSecret())) {
            return out.ok(false)
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(true)
                    .message("API Key или Secret отсутствуют. Сохраните ключи и повторите диагностику.")
                    .build();
        }

        try {
            boolean testnet = s.getNetwork() == NetworkType.TESTNET;
            String base = testnet
                    ? "https://testnet.binance.vision"
                    : "https://api.binance.com";

            long ts = System.currentTimeMillis();
            String query = "recvWindow=5000&timestamp=" + ts;
            String sign = hmacSha256(query, s.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", s.getApiKey());

            String url = base + "/api/v3/account?" + query + "&signature=" + sign;

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>("", headers), String.class);

            // ---- OK ----
            JSONObject json = new JSONObject(resp.getBody());

            boolean canTrade = json.optBoolean("canTrade", false);

            // Binance отдаёт комиссии как целое число → 10 = 0.1%
            int makerRaw = json.optInt("makerCommission", -1);
            int takerRaw = json.optInt("takerCommission", -1);

            double makerPct = makerRaw / 100.0;
            double takerPct = takerRaw / 100.0;

            out.apiKeyValid(true);
            out.secretValid(true);
            out.signatureValid(true);
            out.accountReadable(true);
            out.tradingAllowed(canTrade);
            out.ipAllowed(true); // Если ответ пришёл, IP разрешён
            out.networkOk(true);

            out.message("Ключи работают. Подключение успешно.");

            out.extra(Map.of(
                    "makerCommissionPct", makerPct,
                    "takerCommissionPct", takerPct,
                    "canDeposit", json.optBoolean("canDeposit", false),
                    "canWithdraw", json.optBoolean("canWithdraw", false)
            ));

            return out.ok(true).build();
        }

        // ---- Ошибки Binance ----
        catch (HttpClientErrorException ex) {

            String err = ex.getResponseBodyAsString();
            log.error("❌ Binance diagnostics error: {}", err);

            boolean badKey = err.contains("API-key") || err.contains("-2015");
            boolean badSignature = err.contains("-1022");
            boolean ipProblem = err.contains("IP");

            out.apiKeyValid(!badKey);
            out.secretValid(!badKey);
            out.signatureValid(!badSignature);

            out.accountReadable(!(badKey || badSignature));
            out.tradingAllowed(false);

            out.ipAllowed(!ipProblem);
            out.networkOk(true);

            out.message("Ошибка Binance: " + err);

            return out.ok(false).build();
        }

        catch (Exception ex) {
            return out.ok(false)
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(false)
                    .message("Не удалось выполнить запрос: " + ex.getMessage())
                    .build();
        }
    }

    // ========================================================================
// Детальная диагностика BYBIT (Unified V5)
// ========================================================================
    private ApiKeyDiagnostics testConnectionDetailedBybit(ExchangeSettings s) {

        if (s == null || isBlank(s.getExchange()) || !"BYBIT".equalsIgnoreCase(s.getExchange())) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BYBIT")
                    .message("Биржа указана неверно.")
                    .build();
        }

        if (isBlank(s.getApiKey()) || isBlank(s.getApiSecret())) {
            return ApiKeyDiagnostics.builder()
                    .ok(false)
                    .exchange("BYBIT")
                    .message("API Key или Secret отсутствуют.")
                    .build();
        }

        ApiKeyDiagnostics.ApiKeyDiagnosticsBuilder d =
                ApiKeyDiagnostics.builder().exchange("BYBIT");

        try {
            boolean testnet = s.getNetwork() == NetworkType.TESTNET;

            String base = testnet
                    ? "https://api-testnet.bybit.com"
                    : "https://api.bybit.com";

            long ts = System.currentTimeMillis();
            String recv = "5000";
            String query = "accountType=UNIFIED";

            String preSign = ts + s.getApiKey() + recv + query;
            String sign = hmacSha256(preSign, s.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", s.getApiKey());
            headers.set("X-BAPI-SIGN", sign);
            headers.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            headers.set("X-BAPI-RECV-WINDOW", recv);

            String url = base + "/v5/account/wallet-balance?" + query;

            ResponseEntity<String> resp =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>("", headers), String.class);

            JSONObject json = new JSONObject(resp.getBody());

            if (json.optInt("retCode") != 0) {
                return d.ok(false)
                        .apiKeyValid(false)
                        .secretValid(false)
                        .signatureValid(true)
                        .accountReadable(false)
                        .tradingAllowed(false)
                        .ipAllowed(false)
                        .networkOk(true)
                        .message("Bybit отклонил ключи: " + json.optString("retMsg"))
                        .build();
            }

            d.apiKeyValid(true);
            d.secretValid(true);
            d.signatureValid(true);
            d.accountReadable(true);
            d.networkOk(true);
            d.ipAllowed(true);
            d.tradingAllowed(true);

            JSONObject result = json.optJSONObject("result");
            Object list = (result != null) ? result.opt("list") : null;
            String accountType = (result != null) ? result.optString("accountType", "UNIFIED") : "UNIFIED";

            d.extra(Map.of(
                    "accountType", accountType,
                    "balanceList", list
            ));

            return d.ok(true).message("Подключение к Bybit успешно. Ключи работают.").build();

        } catch (Exception ex) {
            return d.ok(false)
                    .message("Ошибка запроса к Bybit: " + ex.getMessage())
                    .apiKeyValid(false)
                    .secretValid(false)
                    .signatureValid(false)
                    .accountReadable(false)
                    .tradingAllowed(false)
                    .ipAllowed(false)
                    .networkOk(false)
                    .build();
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
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка HMAC-SHA256", e);
        }
    }
}
