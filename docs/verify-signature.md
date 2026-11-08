# Webhook Signature Verification

## Overview

Webhook Relay signs outgoing webhook requests using HMAC-SHA256. Recipients should verify the signature before processing the payload.

This helps detect payload tampering and reject requests with expired timestamps.

## HTTP Headers

Each webhook delivery includes:

```http
Webhook-Id: 550e8400-e29b-41d4-a716-446655440000
Webhook-Timestamp: 1700000000
Webhook-Signature: t=1700000000,v1=<hex-encoded-hmac>
Content-Type: application/json
```

- `Webhook-Id`: Unique delivery identifier, stable across retries.
- `Webhook-Timestamp`: Unix timestamp in seconds.
- `Webhook-Signature`: Timestamp and HMAC-SHA256 signature.

## Signature Generation

The signed message is:

```text
timestamp + "." + rawBody
```

The signature is calculated as:

```text
HMAC-SHA256(endpointSecret, signedMessage)
```

The resulting bytes are encoded as lowercase hexadecimal.

The recipient must use the exact UTF-8 request body bytes. Do not parse and reserialize JSON before verification because whitespace, field ordering, and formatting may change.

## Verification Procedure

1. Read the raw HTTP request body.
2. Extract `Webhook-Timestamp` and `Webhook-Signature`.
3. Reject missing or malformed headers.
4. Reject timestamps more than 300 seconds in the past or future.
5. Reconstruct the signed message using the timestamp and raw body.
6. Compute the expected HMAC-SHA256 using the shared endpoint secret.
7. Compare the provided and expected signatures using a constant-time comparison.
8. Reject invalid requests with HTTP `401 Unauthorized`.

## Java Verification Example

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public class WebhookVerifier {

    public static boolean verify(
            String secret,
            byte[] rawBody,
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
            long now = Instant.now().getEpochSecond();

            if (timestamp < now - 300 || timestamp > now + 300) {
                return false;
            }

            String prefix = "t=" + timestampHeader + ",v1=";

            if (!signatureHeader.startsWith(prefix)) {
                return false;
            }

            byte[] provided = HexFormat.of().parseHex(
                    signatureHeader.substring(prefix.length())
            );

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));

            byte[] timestampBytes = (
                    timestampHeader + "."
            ).getBytes(StandardCharsets.UTF_8);

            mac.update(timestampBytes);
            byte[] expected = mac.doFinal(rawBody);

            return MessageDigest.isEqual(expected, provided);

        } catch (Exception e) {
            return false;
        }
    }
}
```

## Replay Protection

Timestamp validation limits how long a captured request remains valid.

However, a valid request can still be replayed within the five-minute tolerance window.

For stronger replay protection, recipients should persist processed `Webhook-Id` values and reject duplicates.

A retry of the same delivery uses the same `Webhook-Id`, so receivers should handle duplicate delivery attempts idempotently.

## Secret Management

- Store endpoint secrets in environment variables or a secure secret manager.
- Never log secrets or include them in API responses.
- Use HTTPS for webhook delivery.
- Rotate secrets when compromised.
- During rotation, support a controlled overlap period where both the current and previous secrets can be verified.

The current implementation uses one secret per endpoint and does not yet implement automated secret rotation.

## Expected Responses

| Scenario | HTTP Status |
|---|---|
| Valid signature | `200 OK` |
| Invalid signature | `401 Unauthorized` |
| Expired timestamp | `401 Unauthorized` |
| Missing signature | `401 Unauthorized` |
| Simulated receiver failure | `500 Internal Server Error` |

## Testing Scope

The project includes unit tests for signing and verification, plus Spring MVC controller tests for HTTP response behavior.

These tests do not independently prove that a real network delivery succeeds with a matching configured receiver secret. That requires an end-to-end test with the sender and receiver configured to share the same secret.