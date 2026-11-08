package com.example.webhook_relay;

import com.example.webhook_relay.dto.DeliveryResult;
import com.example.webhook_relay.security.WebhookSigner;
import com.example.webhook_relay.service.DeliverySender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DeliverySignatureHttpIntegrationTest {

    @Test
    void signatureMatchesActualReceivedHttpBodyBytes() throws Exception {
        String secret = "test-secret";
        String body = "{\"message\":\"Hello 한글!\"}";
        long timestamp = Instant.now().getEpochSecond();

        WebhookSigner signer = new WebhookSigner();
        String signature = signer.sign(secret, timestamp, body);

        AtomicBoolean verified = new AtomicBoolean(false);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/webhook", exchange -> {
            try {
                byte[] receivedBody =
                        exchange.getRequestBody().readAllBytes();

                String receivedTimestamp = exchange.getRequestHeaders()
                        .getFirst("Webhook-Timestamp");

                String receivedSignature = exchange.getRequestHeaders()
                        .getFirst("Webhook-Signature");

                boolean valid = false;

                try {
                    long parsedTimestamp =
                            Long.parseLong(receivedTimestamp);

                    String prefix =
                            "t=" + receivedTimestamp + ",v1=";

                    if (receivedSignature != null
                            && receivedSignature.startsWith(prefix)
                            && parsedTimestamp == timestamp) {

                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(
                                secret.getBytes(StandardCharsets.UTF_8),
                                "HmacSHA256"
                        ));

                        mac.update(
                                (receivedTimestamp + ".")
                                        .getBytes(StandardCharsets.UTF_8)
                        );

                        byte[] expected = mac.doFinal(receivedBody);

                        byte[] provided = HexFormat.of().parseHex(
                                receivedSignature.substring(prefix.length())
                        );

                        valid = MessageDigest.isEqual(
                                expected, provided
                        );
                    }
                } catch (Exception ignored) {
                    // Malformed signature is rejected.
                }

                verified.set(valid);

                byte[] response = (
                        valid ? "Verified" : "Invalid signature"
                ).getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(
                        valid ? 200 : 401,
                        response.length
                );

                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            String url = "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/webhook";

            DeliveryResult result = new DeliverySender().send(
                    url,
                    body,
                    Map.of(
                            "Content-Type", "application/json",
                            "Webhook-Id", "test-delivery-id",
                            "Webhook-Timestamp", String.valueOf(timestamp),
                            "Webhook-Signature",
                            "t=" + timestamp + ",v1=" + signature
                    )
            );

            assertEquals(200, result.statusCode());
            assertTrue(verified.get());

        } finally {
            server.stop(0);
        }
    }
    @Test
    void tamperedBodyIsRejectedOverHttp() throws Exception {
        String secret = "test-secret";
        long timestamp = Instant.now().getEpochSecond();

        String originalBody = "{\"amount\":100}";
        String tamperedBody = "{\"amount\":900}";

        String signature = new WebhookSigner().sign(
                secret,
                timestamp,
                originalBody
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/webhook", exchange -> {
            try {
                byte[] receivedBody =
                        exchange.getRequestBody().readAllBytes();

                String receivedTimestamp = exchange.getRequestHeaders()
                        .getFirst("Webhook-Timestamp");

                String receivedSignature = exchange.getRequestHeaders()
                        .getFirst("Webhook-Signature");

                boolean valid = false;

                try {
                    String prefix = "t=" + receivedTimestamp + ",v1=";

                    if (receivedSignature != null
                            && receivedSignature.startsWith(prefix)) {

                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(
                                secret.getBytes(StandardCharsets.UTF_8),
                                "HmacSHA256"
                        ));

                        mac.update(
                                (receivedTimestamp + ".")
                                        .getBytes(StandardCharsets.UTF_8)
                        );

                        byte[] expected = mac.doFinal(receivedBody);

                        byte[] provided = HexFormat.of().parseHex(
                                receivedSignature.substring(prefix.length())
                        );

                        valid = MessageDigest.isEqual(expected, provided);
                    }
                } catch (Exception ignored) {
                    // Invalid signatures are rejected.
                }

                byte[] response = (valid ? "Verified" : "Invalid signature")
                        .getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(
                        valid ? 200 : 401,
                        response.length
                );

                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            String url = "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/webhook";

            DeliveryResult result = new DeliverySender().send(
                    url,
                    tamperedBody,
                    Map.of(
                            "Content-Type", "application/json",
                            "Webhook-Id", "test-delivery-id",
                            "Webhook-Timestamp", String.valueOf(timestamp),
                            "Webhook-Signature",
                            "t=" + timestamp + ",v1=" + signature
                    )
            );

            assertEquals(401, result.statusCode());

        } finally {
            server.stop(0);
        }
    }
}