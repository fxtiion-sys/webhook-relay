
package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.SQLOutput;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true"
)
public class DeliveryWorker {

    private final DeliveryClaimService claimService;
    private final DeliverySender deliverySender;
    private final DeliveryResultService resultService;

    public DeliveryWorker(
            DeliveryClaimService claimService,
            DeliverySender deliverySender,
            DeliveryResultService resultService
    ) {
        this.claimService = claimService;
        this.deliverySender = deliverySender;
        this.resultService = resultService;
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
                DeliveryResult result = deliverySender.send(
                        delivery.url(),
                        delivery.body(),
                        Map.of("Content-Type", "application/json")
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
