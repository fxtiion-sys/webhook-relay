package com.example.webhook_relay.dto;

public record DeliveryResult(
        Integer statusCode,
        String error,
        long durationMs
) {
}