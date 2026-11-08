package com.example.webhook_relay.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = "{\"event\":\"created\"}";
    private static final long NOW = 1700000000L;

    private final WebhookSigner signer = new WebhookSigner();

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(
                    signer,
                    Clock.fixed(
                            Instant.ofEpochSecond(NOW),
                            ZoneOffset.UTC
                    )
            );

    private String signatureHeader(long timestamp, String body) {
        return "t=" + timestamp + ",v1="
                + signer.sign(SECRET, timestamp, body);
    }

    @Test
    void validSignatureIsAccepted() {
        assertTrue(verifier.verify(
                SECRET,
                BODY,
                String.valueOf(NOW),
                signatureHeader(NOW, BODY)
        ));
    }

    @Test
    void modifiedBodyIsRejected() {
        assertFalse(verifier.verify(
                SECRET,
                "{\"event\":\"updated\"}",
                String.valueOf(NOW),
                signatureHeader(NOW, BODY)
        ));
    }

    @Test
    void incorrectSecretIsRejected() {
        assertFalse(verifier.verify(
                "wrong-secret",
                BODY,
                String.valueOf(NOW),
                signatureHeader(NOW, BODY)
        ));
    }

    @Test
    void expiredTimestampIsRejected() {
        long expired = NOW - 301;

        assertFalse(verifier.verify(
                SECRET,
                BODY,
                String.valueOf(expired),
                signatureHeader(expired, BODY)
        ));
    }

    @Test
    void futureTimestampIsRejected() {
        long future = NOW + 301;

        assertFalse(verifier.verify(
                SECRET,
                BODY,
                String.valueOf(future),
                signatureHeader(future, BODY)
        ));
    }

    @Test
    void missingSignatureIsRejected() {
        assertFalse(verifier.verify(
                SECRET,
                BODY,
                String.valueOf(NOW),
                null
        ));
    }

    @Test
    void malformedSignatureIsRejected() {
        assertFalse(verifier.verify(
                SECRET,
                BODY,
                String.valueOf(NOW),
                "invalid-signature"
        ));
    }
}
