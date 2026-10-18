package com.example.webhook_relay.dto;

import java.util.List;
import java.util.UUID;

public record EndpointResponse(
        UUID id,
        String url,
        List<String> eventTypes
) {
}