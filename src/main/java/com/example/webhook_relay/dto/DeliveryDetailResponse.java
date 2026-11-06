package com.example.webhook_relay.dto;

import java.util.List;

public record DeliveryDetailResponse(
        DeliveryResponse delivery,
        List<DeliveryAttemptResponse> attempts
) {
}
