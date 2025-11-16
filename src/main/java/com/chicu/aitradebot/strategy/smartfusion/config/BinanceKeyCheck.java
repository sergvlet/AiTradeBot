package com.chicu.aitradebot.strategy.smartfusion.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.http.*;
import java.net.URI;

public class BinanceKeyCheck {
    public static void main(String[] args) throws Exception {
        String apiKey = "JMOe4V5XIBmvu9vC90g1rsrMKOdJe0tbrflQJZjLHbZKgHv26VQMed7cJ7ZhjQFv";
        String secret = "AkMkTQpJ29OR6edjbCZMmEJsRU0jXInBnuJhi2kTijycF0jliDdnBpHo2SSkMV2P";
        String baseUrl = "https://api.binance.com";
        String endpoint = "/api/v3/account";

        long timestamp = System.currentTimeMillis();
        String query = "timestamp=" + timestamp;
        String signature = hmacSHA256(query, secret);
        String url = baseUrl + endpoint + "?" + query + "&signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", apiKey)
                .GET()
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode() + " → " + response.body());
    }

    private static String hmacSHA256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format("%02x", b));
        return result.toString();
    }
}
