
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

import com.example.webhook_relay.service.DeliveryReplayService;

@RestController
@RequestMapping("/deliveries")
public class DeliveryController {

    private final DeliveryQueryService deliveryQueryService;
    private final DeliveryReplayService deliveryReplayService;

    public DeliveryController(
            DeliveryQueryService deliveryQueryService,
            DeliveryReplayService deliveryReplayService) {
        this.deliveryQueryService = deliveryQueryService;
        this.deliveryReplayService = deliveryReplayService;
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

    @PostMapping("/{id}/replay")
    public ResponseEntity<Void> replayDelivery(@PathVariable UUID id) {
        deliveryReplayService.replay(id);
        return ResponseEntity.accepted().build();
    }
}
