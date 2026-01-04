package com.chicu.aitradebot.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketEndpointSmokeTest {

    @LocalServerPort
    int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void sockJsInfoShouldReturn200() {
        ResponseEntity<String> resp =
                rest.getForEntity("http://localhost:" + port + "/ws/strategy/info", String.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        // обычно там JSON с websocket:true и cookie_needed:false
        assertTrue(resp.getBody().contains("websocket"));
    }
}
