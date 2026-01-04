package com.chicu.aitradebot.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthSmokeTest {

    @LocalServerPort
    int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void actuatorHealthShouldBeUp() {
        ResponseEntity<Map> resp = rest.getForEntity("http://localhost:" + port + "/actuator/health", Map.class);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("UP", resp.getBody().get("status"));
    }
}
