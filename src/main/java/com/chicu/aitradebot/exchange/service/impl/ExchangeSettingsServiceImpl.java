package com.chicu.aitradebot.exchange.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.binance.BinanceExchangeClient;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;
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

        if (target.getCreatedAt() == null)
            target.setCreatedAt(Instant.now());

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





    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .ifPresent(repository::delete);

        log.warn("🗑 Deleted ExchangeSettings {}@{} (chatId={})",
                exchange, network, chatId);
    }



    // ========================================================================
    // testConnection
    // ========================================================================
    @Override
    public boolean testConnection(ExchangeSettings s) {

        if (s == null || isBlank(s.getExchange()) || s.getNetwork() == null)
            return false;

        if (isBlank(s.getApiKey()) || isBlank(s.getApiSecret()))
            return false;

        return switch (s.getExchange().toUpperCase()) {
            case "BINANCE" -> testBinanceConnection(s);
            case "BYBIT"   -> testBybitConnection(s);
            default -> false;
        };
    }

    // ========================================================================
    // Binance simple test
    // ========================================================================
    private boolean testBinanceConnection(ExchangeSettings s) {

        String baseUrl = s.getNetwork() == NetworkType.TESTNET
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
            return false;
        }
    }

    // ========================================================================
    // Bybit simple test
    // ========================================================================
    private boolean testBybitConnection(ExchangeSettings s) {
        String baseUrl = s.getNetwork() == NetworkType.TESTNET
                ? "https://api-testnet.bybit.com"
                : "https://api.bybit.com";

        long ts = System.currentTimeMillis();
        String recvWindow = "5000";
        String query = "accountType=UNIFIED";

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

            if (!resp.getStatusCode().is2xxSuccessful())
                return false;

            JSONObject json = new JSONObject(resp.getBody());
            return json.optInt("retCode", -1) == 0;

        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    // Detailed diagnostics
    // ========================================================================
    @Override
    public BinanceConnectionStatus testConnectionDetailed(ExchangeSettings settings) {

        if (settings == null ||
            !"BINANCE".equalsIgnoreCase(settings.getExchange()) ||
            isBlank(settings.getApiKey()) ||
            isBlank(settings.getApiSecret())) {

            return BinanceConnectionStatus.builder()
                    .ok(false)
                    .message("Ключи отсутствуют или биржа не Binance")
                    .build();
        }

        try {
            BinanceExchangeClient client = new BinanceExchangeClient(this);

            return client.extendedTestConnection(
                    settings.getApiKey(),
                    settings.getApiSecret(),
                    settings.getNetwork() == NetworkType.TESTNET
            );

        } catch (Exception ex) {
            return BinanceConnectionStatus.builder()
                    .ok(false)
                    .message("Ошибка: " + ex.getMessage())
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
            for (byte b : h)
                sb.append(String.format("%02x", b));

            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка HMAC-SHA256", e);
        }
    }
}
