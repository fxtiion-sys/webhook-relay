package com.example.webhook_relay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

public class WebhookSignatureVerifier {

    private static final long TOLERANCE_SECONDS = 300;

    private final WebhookSigner signer;
    private final Clock clock;

    public WebhookSignatureVerifier() {
        this(new WebhookSigner(), Clock.systemUTC());
    }

    public WebhookSignatureVerifier(WebhookSigner signer, Clock clock) {
        this.signer = signer;
        this.clock = clock;
    }

    public boolean verify(
            String secret,
            String rawBody,
            String timestampHeader,
            String signatureHeader
    ) {
        if (secret == null || secret.isBlank()
                || timestampHeader == null
                || signatureHeader == null) {
            return false;
        }

        try {
            long timestamp = Long.parseLong(timestampHeader);

            long now = clock.instant().getEpochSecond();

            if (timestamp < now - TOLERANCE_SECONDS
                    || timestamp > now + TOLERANCE_SECONDS) {
                return false;
            }

            String prefix = "t=" + timestampHeader + ",v1=";

            if (!signatureHeader.startsWith(prefix)) {
                return false;
            }

            String providedHex =
                    signatureHeader.substring(prefix.length());

            byte[] provided = HexFormat.of().parseHex(providedHex);

            String expectedHex = signer.sign(
                    secret,
                    timestamp,
                    rawBody
            );

            byte[] expected = HexFormat.of().parseHex(expectedHex);

            return MessageDigest.isEqual(expected, provided);

        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}