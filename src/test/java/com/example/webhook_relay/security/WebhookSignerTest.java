package com.example.webhook_relay.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void sameInputProducesSameSignature() {
        String first = signer.sign(
                "test-secret",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        String second = signer.sign(
                "test-secret",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        assertEquals(first, second);
    }

    @Test
    void differentSecretProducesDifferentSignature() {
        String first = signer.sign(
                "secret-one",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        String second = signer.sign(
                "secret-two",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        assertNotEquals(first, second);
    }

    @Test
    void differentTimestampProducesDifferentSignature() {
        String first = signer.sign(
                "test-secret",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        String second = signer.sign(
                "test-secret",
                1700000001L,
                "{\"event\":\"created\"}"
        );

        assertNotEquals(first, second);
    }

    @Test
    void differentBodyProducesDifferentSignature() {
        String first = signer.sign(
                "test-secret",
                1700000000L,
                "{\"event\":\"created\"}"
        );

        String second = signer.sign(
                "test-secret",
                1700000000L,
                "{\"event\":\"updated\"}"
        );

        assertNotEquals(first, second);
    }

    @Test
    void signatureIs64CharacterHexString() {
        String signature = signer.sign(
                "test-secret",
                1700000000L,
                "{}"
        );

        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void matchesIndependentHmacSha256Reference() throws Exception {
        String secret = "test-secret";
        long timestamp = 1700000000L;
        String body = "{\"event\":\"created\"}";

        String message = timestamp + "." + body;

        javax.crypto.Mac mac =
                javax.crypto.Mac.getInstance("HmacSHA256");

        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        String expected = java.util.HexFormat.of().formatHex(
                mac.doFinal(
                        message.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
        );

        assertEquals(expected, signer.sign(secret, timestamp, body));
    }

}