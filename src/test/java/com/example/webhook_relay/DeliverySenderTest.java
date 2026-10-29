
package com.example.webhook_relay;

import com.example.webhook_relay.dto.DeliveryResult;
import com.example.webhook_relay.service.DeliverySender;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeliverySenderTest {

    private final DeliverySender sender =
            new DeliverySender();

    @Test
    void sendWebhook() {

        DeliveryResult result = sender.send(
                "http://localhost:8080/mock-receiver",
                "{\"message\":\"Hello from Java!\"}",
                Map.of("Content-Type", "application/json")
        );

        System.out.println("Status: " + result.statusCode());
        System.out.println("Error: " + result.error());
        System.out.println("Duration: " + result.durationMs() + "ms");

        assertEquals(200, result.statusCode());
        assertNull(result.error());
    }

    @Test
    void sendWebhookServerError() {
        DeliveryResult result = sender.send(
                "http://localhost:8080/mock-receiver?fail=500",
                "{\"message\":\"Test failure\"}",
                Map.of("Content-Type", "application/json")
        );

        System.out.println("Status: " + result.statusCode());
        System.out.println("Error: " + result.error());
        System.out.println("Duration: " + result.durationMs() + "ms");

        assertEquals(500, result.statusCode());
        assertNotNull(result.error());
    }

    @Test
    void sendWebhookTimeout() {
        DeliveryResult result = sender.send(
                "http://localhost:8080/mock-receiver?delayMs=3000",
                "{\"message\":\"Test timeout\"}",
                Map.of("Content-Type", "application/json")
        );

        System.out.println("Status: " + result.statusCode());
        System.out.println("Error: " + result.error());
        System.out.println("Duration: " + result.durationMs() + "ms");

        assertNull(result.statusCode());
        assertNotNull(result.error());
    }

}
