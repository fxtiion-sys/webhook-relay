package com.example.webhook_relay.retry;

import com.example.webhook_relay.dto.DeliveryResult;

public class FailureClassifier {

    public DeliveryOutcome classify(DeliveryResult result) {
        Integer statusCode = result.statusCode();

        if (statusCode == null) {
            return DeliveryOutcome.RETRYABLE;
        }

        if (statusCode >= 200 && statusCode < 300) {
            return DeliveryOutcome.SUCCESS;
        }

        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return DeliveryOutcome.RETRYABLE;
        }

        return DeliveryOutcome.PERMANENT_FAILURE;
    }
}