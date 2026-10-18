package com.example.webhook_relay.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.net.URI;
import java.util.List;

public record CreateEndpointRequest(

        @NotBlank(message = "URL is required")
        String url,

        @NotEmpty(message = "At least one event type is required")
        List<@NotBlank(message = "Event type cannot be blank") String> eventTypes

) {

        @AssertTrue(message = "URL must be a valid HTTP or HTTPS URL")
        public boolean isValidUrl() {

                if (url == null || url.isBlank()) {
                        return true;
                }

                try {
                        URI uri = URI.create(url);

                        String scheme = uri.getScheme();

                        return ("http".equalsIgnoreCase(scheme)
                                || "https".equalsIgnoreCase(scheme))
                                && uri.getHost() != null
                                && !uri.getHost().isBlank()
                                && uri.getRawUserInfo() == null
                                && uri.getRawFragment() == null;

                } catch (IllegalArgumentException e) {
                        return false;
                }
        }
}