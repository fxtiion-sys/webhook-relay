package com.example.webhook_relay.dto;

import java.util.UUID;

public record CreateEventResponse(
        UUID eventId
) {}