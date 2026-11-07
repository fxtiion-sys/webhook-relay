package com.example.webhook_relay.retry;

import com.example.webhook_relay.dto.DeliveryResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    private void assertOutcome(
            Integer statusCode,
            DeliveryOutcome expected
    ) {
        DeliveryResult result = new DeliveryResult(
                statusCode,
                null,
                100L
        );

        assertEquals(expected, classifier.classify(result));
    }

    @Test
    void successfulResponses() {
        assertOutcome(200, DeliveryOutcome.SUCCESS);
        assertOutcome(201, DeliveryOutcome.SUCCESS);
        assertOutcome(204, DeliveryOutcome.SUCCESS);
    }

    @Test
    void serverErrorsAreRetryable() {
        assertOutcome(500, DeliveryOutcome.RETRYABLE);
        assertOutcome(502, DeliveryOutcome.RETRYABLE);
        assertOutcome(503, DeliveryOutcome.RETRYABLE);
    }

    @Test
    void timeoutAndRateLimitAreRetryable() {
        assertOutcome(408, DeliveryOutcome.RETRYABLE);
        assertOutcome(429, DeliveryOutcome.RETRYABLE);
    }

    @Test
    void otherClientErrorsArePermanent() {
        assertOutcome(400, DeliveryOutcome.PERMANENT_FAILURE);
        assertOutcome(401, DeliveryOutcome.PERMANENT_FAILURE);
        assertOutcome(403, DeliveryOutcome.PERMANENT_FAILURE);
        assertOutcome(404, DeliveryOutcome.PERMANENT_FAILURE);
        assertOutcome(422, DeliveryOutcome.PERMANENT_FAILURE);
    }

    @Test
    void networkErrorsAreRetryable() {
        assertOutcome(null, DeliveryOutcome.RETRYABLE);
    }
}