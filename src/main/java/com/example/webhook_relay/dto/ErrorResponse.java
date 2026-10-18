package com.example.webhook_relay.dto;

import java.util.List;

public record ErrorResponse(
        int status,
        String message,
        List<String> errors
) {
}