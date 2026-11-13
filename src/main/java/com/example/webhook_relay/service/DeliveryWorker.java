
package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.example.webhook_relay.security.WebhookSigner;
import java.time.Instant;

import com.example.webhook_relay.ratelimit.TokenBucketRateLimiter;
import java.time.Duration;

@Service
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true"
)
public class DeliveryWorker {

    private final DeliveryClaimService claimService;
    private final DeliverySender deliverySender;
    private final DeliveryResultService resultService;
    private final WebhookSigner webhookSigner = new WebhookSigner();
    private final TokenBucketRateLimiter rateLimiter;

    public DeliveryWorker(
            DeliveryClaimService claimService,
            DeliverySender deliverySender,
            DeliveryResultService resultService,
            TokenBucketRateLimiter rateLimiter
    ) {
        this.claimService = claimService;
        this.deliverySender = deliverySender;
        this.resultService = resultService;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelay = 500)
    public void processDeliveries() {

        int cleaned = claimService.cleanupExpiredDeliveries();

        if (cleaned > 0) {
            System.out.println(
                    "Expired deliveries marked DEAD: " + cleaned
            );
        }

        var deliveries = claimService.claimDeliveries();

        for (var delivery : deliveries) {
            try {
                boolean allowed = rateLimiter.tryAcquire(
                        delivery.endpointId(),
                        delivery.rateLimitPerSec()
                );

                if (!allowed) {
                    boolean deferred = resultService.deferRateLimitedDelivery(
                            delivery.id(),
                            delivery.attemptNo(),
                            delivery.lockedUntil(),
                            Duration.ofMillis(500)
                    );

                    if (!deferred) {
                        System.out.println(
                                "Stale rate-limited delivery ignored: "
                                        + delivery.id()
                        );
                    }

                    continue;
                }

                long timestamp = Instant.now().getEpochSecond();

                String signature = webhookSigner.sign(
                        delivery.secret(),
                        timestamp,
                        delivery.body()
                );

                Map<String, String> headers = Map.of(
                        "Content-Type", "application/json",
                        "Webhook-Id", delivery.id().toString(),
                        "Webhook-Timestamp", String.valueOf(timestamp),
                        "Webhook-Signature", "t=" + timestamp + ",v1=" + signature
                );

                DeliveryResult result = deliverySender.send(
                        delivery.url(),
                        delivery.body(),
                        headers
                );

                boolean saved = resultService.saveResult(
                        delivery.id(),
                        delivery.attemptNo(),
                        delivery.lockedUntil(),
                        result
                );

                if (saved) {
                    System.out.println(
                            "Delivery processed: " + delivery.id()
                                    + " HTTP " + result.statusCode()
                    );
                } else {
                    System.out.println(
                            "Stale delivery result ignored: "
                                    + delivery.id()
                    );
                }

            } catch (Exception e) {
                System.err.println(
                        "Worker error for delivery "
                                + delivery.id() + ": " + e.getMessage()
                );
            }
        }
    }
}
