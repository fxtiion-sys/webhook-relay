package com.example.webhook_relay.retry;

public enum DeliveryOutcome {
    SUCCESS,
    RETRYABLE,
    PERMANENT_FAILURE
}