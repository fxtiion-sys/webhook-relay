package com.example.webhook_relay.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public String sign(
            String secret,
            long timestamp,
            String rawBody
    ) {
        try {
            String message = timestamp + "." + rawBody;

            Mac mac = Mac.getInstance(ALGORITHM);

            SecretKeySpec key = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    ALGORITHM
            );

            mac.init(key);

            byte[] signature = mac.doFinal(
                    message.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(signature);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to generate webhook signature", e
            );
        }
    }
}