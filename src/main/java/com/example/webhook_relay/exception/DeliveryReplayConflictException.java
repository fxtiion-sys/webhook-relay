package com.example.webhook_relay.exception;

public class DeliveryReplayConflictException extends RuntimeException {

    public DeliveryReplayConflictException(String message) {
        super(message);
    }
}