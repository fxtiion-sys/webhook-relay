
package com.example.webhook_relay;

import com.example.webhook_relay.dto.DeliveryResult;
import com.example.webhook_relay.service.DeliverySender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DeliverySenderTest {

    private static HttpServer server;
    private static String baseUrl;

    private final DeliverySender sender = new DeliverySender();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/mock-receiver", exchange -> {
            String query = exchange.getRequestURI().getQuery();

            int status = 200;
            long delayMs = 0;

            if (query != null) {
                if (query.contains("fail=500")) {
                    status = 500;
                }

                if (query.contains("delayMs=3000")) {
                    delayMs = 3000;
                }
            }

            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }

                byte[] response = (
                        status == 500
                                ? "Simulated server error"
                                : "Received"
                ).getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(status, response.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The client may disconnect after a timeout.
            } finally {
                exchange.close();
            }
        });

        server.start();

        baseUrl = "http://127.0.0.1:"
                + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendWebhook() {
        DeliveryResult result = sender.send(
                baseUrl + "/mock-receiver",
                "{\"message\":\"Hello from Java!\"}",
                Map.of("Content-Type", "application/json")
        );

        System.out.println("Status: " + result.statusCode());
        System.out.println("Error: " + result.error());

        assertEquals(200, result.statusCode());
        assertNull(result.error());
    }

    @Test
    void sendWebhookServerError() {
        DeliveryResult result = sender.send(
                baseUrl + "/mock-receiver?fail=500",
                "{\"message\":\"Test failure\"}",
                Map.of("Content-Type", "application/json")
        );

        System.out.println("Status: " + result.statusCode());
        System.out.println("Error: " + result.error());

        assertEquals(500, result.statusCode());
        assertNotNull(result.error());
    }

    @Test
    void sendWebhookTimeout() {
        DeliveryResult result = sender.send(
                baseUrl + "/mock-receiver?delayMs=3000",
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
