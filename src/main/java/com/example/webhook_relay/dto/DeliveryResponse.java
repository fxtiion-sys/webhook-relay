
package com.example.webhook_relay.dto;

import com.example.webhook_relay.entity.Delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID eventId,
        UUID endpointId,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeliveryResponse from(Delivery delivery) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getEvent().getId(),
                delivery.getEndpoint().getId(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastStatusCode(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt()
        );
    }
}
