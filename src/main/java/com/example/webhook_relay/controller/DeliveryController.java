
package com.example.webhook_relay.controller;

import com.example.webhook_relay.dto.DeliveryDetailResponse;
import com.example.webhook_relay.dto.DeliveryResponse;
import com.example.webhook_relay.service.DeliveryQueryService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/deliveries")
public class DeliveryController {

    private final DeliveryQueryService deliveryQueryService;

    public DeliveryController(DeliveryQueryService deliveryQueryService) {
        this.deliveryQueryService = deliveryQueryService;
    }

    @GetMapping
    public ResponseEntity<Page<DeliveryResponse>> getDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );

        Page<DeliveryResponse> deliveries =
                deliveryQueryService.getDeliveries(
                        status,
                        endpointId,
                        pageable
                );

        return ResponseEntity.ok(deliveries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryDetailResponse> getDeliveryById(
            @PathVariable UUID id
    ) {
        DeliveryDetailResponse delivery =
                deliveryQueryService.getDeliveryById(id);

        return ResponseEntity.ok(delivery);
    }

}
