
package com.example.webhook_relay.dto;

import com.example.webhook_relay.entity.DeliveryAttempt;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        int attemptNo,
        Integer statusCode,
        String error,
        long durationMs,
        Instant attemptedAt
) {
    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNo(),
                attempt.getStatusCode(),
                attempt.getError(),
                attempt.getDurationMs(),
                attempt.getAttemptedAt()
        );
    }
}
