package com.example.webhook_relay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CreateEventRequest(

        @NotBlank
        String type,

        @NotNull
        JsonNode payload

) {}