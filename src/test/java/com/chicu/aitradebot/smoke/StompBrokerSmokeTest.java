package com.chicu.aitradebot.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.*;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompBrokerSmokeTest {

    @LocalServerPort
    int port;

    @Test
    void shouldConnectSubscribeAndReceive() throws Exception {
        String topic = "/topic/_smoke/test";
        String appDest = "/app/_smoke/echo";

        Map<String, Object> payload = Map.of(
                "type", "smoke",
                "ts", System.currentTimeMillis(),
                "id", UUID.randomUUID().toString()
        );

        WebSocketStompClient client = buildSockJsStompClient();
        StompSession session = null;

        try {
            session = connect(client);

            CompletableFuture<Map<String, Object>> received = new CompletableFuture<>();

            session.subscribe(topic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @SuppressWarnings("unchecked")
                @Override
                public void handleFrame(StompHeaders headers, Object p) {
                    if (p instanceof Map<?, ?> m) {
                        received.complete((Map<String, Object>) m);
                    } else {
                        received.completeExceptionally(
                                new AssertionError("Payload type: " + (p == null ? "null" : p.getClass()))
                        );
                    }
                }
            });

            // ✅ ВАЖНО: отправляем после subscribe из того же STOMP-соединения
            session.send(appDest, payload);

            Map<String, Object> msg = received.get(3, TimeUnit.SECONDS);
            assertEquals("smoke", msg.get("type"));
            assertTrue(msg.containsKey("ts"));
            assertEquals(payload.get("id"), msg.get("id"));

        } finally {
            if (session != null && session.isConnected()) {
                try { session.disconnect(); } catch (Exception ignored) {}
            }
            try { client.stop(); } catch (Exception ignored) {}
        }
    }

    private WebSocketStompClient buildSockJsStompClient() {
        SockJsClient sockJs = new SockJsClient(List.of(
                new WebSocketTransport(new StandardWebSocketClient())
        ));
        WebSocketStompClient stomp = new WebSocketStompClient(sockJs);
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        return stomp;
    }

    private StompSession connect(WebSocketStompClient client) throws Exception {
        String url = "http://localhost:" + port + "/ws/strategy";
        CompletableFuture<StompSession> fut =
                client.connectAsync(url, new StompSessionHandlerAdapter() {});
        return fut.get(3, TimeUnit.SECONDS);
    }

    // =========================================================
    // ✅ Тестовый echo-handler: /app/_smoke/echo -> /topic/_smoke/test
    // =========================================================
    @TestConfiguration
    static class SmokeWsTestConfig {
        @Controller
        static class SmokeEchoController {

            @MessageMapping("/_smoke/echo")
            @SendTo("/topic/_smoke/test")
            public Map<String, Object> echo(Map<String, Object> payload) {
                return payload;
            }
        }
    }
}
