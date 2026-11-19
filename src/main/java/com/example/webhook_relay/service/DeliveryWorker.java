
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

import com.example.webhook_relay.metrics.DeliveryMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import java.util.UUID;

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
    private final DeliveryMetrics deliveryMetrics;
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final String workerId;

    public DeliveryWorker(
            DeliveryClaimService claimService,
            DeliverySender deliverySender,
            DeliveryResultService resultService,
            TokenBucketRateLimiter rateLimiter,
            DeliveryMetrics deliveryMetrics,
            @Value("${app.worker.id:}") String configuredWorkerId

    ) {
        this.claimService = claimService;
        this.deliverySender = deliverySender;
        this.resultService = resultService;
        this.rateLimiter = rateLimiter;
        this.deliveryMetrics = deliveryMetrics;
        this.workerId = configuredWorkerId.isBlank()
                ? "worker-" + UUID.randomUUID()
                : configuredWorkerId;
    }

    @Scheduled(fixedDelay = 500)
    public void processDeliveries() {

        int cleaned = claimService.cleanupExpiredDeliveries();

        if (cleaned > 0) {
            log.info(
                    "event=expired_deliveries_cleaned workerId={} count={}",
                    workerId,
                    cleaned
            );
        }

        var deliveries = claimService.claimDeliveries();

        for (var delivery : deliveries) {
            try (
                    MDC.MDCCloseable workerContext =
                            MDC.putCloseable("workerId", workerId);
                    MDC.MDCCloseable deliveryContext =
                            MDC.putCloseable("deliveryId", delivery.id().toString())
            ) {
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
                        log.warn(
                                "event=stale_rate_limited_delivery workerId={} deliveryId={}",
                                workerId,
                                delivery.id()
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

                deliveryMetrics.recordHttpDuration(result.durationMs());

                boolean saved = resultService.saveResult(
                        delivery.id(),
                        delivery.attemptNo(),
                        delivery.lockedUntil(),
                        result
                );

                if (saved) {
                    log.info(
                            "event=delivery_processed workerId={} deliveryId={} attempt={} httpStatus={}",
                            workerId,
                            delivery.id(),
                            delivery.attemptNo(),
                            result.statusCode()
                    );
                } else {
                    log.warn(
                            "event=stale_delivery_result workerId={} deliveryId={}",
                            workerId,
                            delivery.id()
                    );
                }

            } catch (Exception e) {
                log.error(
                        "event=delivery_processing_error workerId={} deliveryId={}",
                        workerId,
                        delivery.id(),
                        e
                );
            }
        }
    }
}
