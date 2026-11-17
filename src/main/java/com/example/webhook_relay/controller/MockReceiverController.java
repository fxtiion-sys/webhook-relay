package com.example.webhook_relay.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.webhook_relay.security.WebhookSignatureVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.webhook_relay.service.LocalDemoSecretResolver;
import org.springframework.beans.factory.ObjectProvider;

@RestController
@RequestMapping("/mock-receiver")
public class MockReceiverController {

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestBody String body,
            @RequestHeader(value = "Webhook-Id", required = false)
            String webhookId,
            @RequestHeader(value = "Webhook-Timestamp", required = false)
            String timestamp,
            @RequestHeader(value = "Webhook-Signature", required = false)
            String signature,
            @RequestParam(required = false) Integer fail,
            @RequestParam(defaultValue = "0") long delayMs
    ) throws InterruptedException {
        //1. Determine the signing secret
        String secret = mockSecret;

        LocalDemoSecretResolver resolver = secretResolver.getIfAvailable();

        if (resolver != null && webhookId != null && !webhookId.isBlank()) {
            secret = resolver.resolve(webhookId).orElse(null);
        } else if (resolver != null) {
            secret = null;
        }
        // 2. Verify HMAC signature
        if (webhookId == null || webhookId.isBlank()
                || secret == null
                || !verifier.verify(secret, body, timestamp, signature)) {
            return ResponseEntity.status(401)
                    .body("Invalid webhook signature");
        }

        // 3. Simulate delay
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        // 4. Simulate HTTP 500
        if (fail != null && fail == 500) {
            return ResponseEntity.status(500)
                    .body("Simulated server error");
        }

        return ResponseEntity.ok("Received");
    }

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier();

    private final String mockSecret;
    private final ObjectProvider<LocalDemoSecretResolver> secretResolver;

    public MockReceiverController(
            @Value("${app.mock-receiver.secret}") String mockSecret,
            ObjectProvider<LocalDemoSecretResolver> secretResolver
    ) {
        this.mockSecret = mockSecret;
        this.secretResolver = secretResolver;
    }

}