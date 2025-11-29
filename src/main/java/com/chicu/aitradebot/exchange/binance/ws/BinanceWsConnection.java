package com.chicu.aitradebot.exchange.binance.ws;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

@Slf4j
public class BinanceWsConnection implements WebSocket.Listener {

    private final URI uri;
    private final Consumer<String> messageHandler;

    private WebSocket webSocket;
    private volatile boolean closed = false;

    public BinanceWsConnection(URI uri, Consumer<String> messageHandler) {
        this.uri = uri;
        this.messageHandler = messageHandler;
        connect();
    }

    private void connect() {
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, this)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    log.info("🌐 WS OPEN {}", uri);
                })
                .exceptionally(ex -> {
                    log.error("❌ WS connection failed {}: {}", uri, ex.getMessage());
                    reconnect();
                    return null;
                });
    }

    public void reconnect() {
        if (closed) return;

        log.warn("🔄 Reconnecting WS {}", uri);

        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {}

        connect();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        log.info("🟢 WS CONNECTED {}", uri);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket,
                                     CharSequence data,
                                     boolean last) {
        try {
            messageHandler.accept(data.toString());
        } catch (Exception e) {
            log.error("❌ WS message error: {}", e.getMessage());
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket,
                                       ByteBuffer data,
                                       boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket,
                                     ByteBuffer message) {
        webSocket.sendPong(message);
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket,
                                     ByteBuffer message) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket,
                                      int statusCode,
                                      String reason) {
        log.warn("⚠ WS CLOSED {} code={} reason={}", uri, statusCode, reason);
        if (!closed) reconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.error("❌ WS ERROR {} {}", uri, error.getMessage());
        if (!closed) reconnect();
    }

    public void close() {
        closed = true;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Manual close");
            } catch (Exception ignored) {}
        }
    }
}
