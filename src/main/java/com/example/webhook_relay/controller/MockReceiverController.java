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

        if (webhookId == null || webhookId.isBlank()
                || !verifier.verify(
                mockSecret,
                body,
                timestamp,
                signature
        )) {
            return ResponseEntity.status(401)
                    .body("Invalid webhook signature");
        }

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }

        if (fail != null && fail == 500) {
            return ResponseEntity.status(500)
                    .body("Simulated server error");
        }

        return ResponseEntity.ok("Received");
    }

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier();

    private final String mockSecret;

    public MockReceiverController(
            @Value("${app.mock-receiver.secret}") String mockSecret
    ) {
        this.mockSecret = mockSecret;
    }

}