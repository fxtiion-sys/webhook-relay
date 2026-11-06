
package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResponse;
import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.repository.DeliveryRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.example.webhook_relay.dto.DeliveryDetailResponse;
import com.example.webhook_relay.dto.DeliveryAttemptResponse;
import com.example.webhook_relay.repository.DeliveryAttemptRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@Service
public class DeliveryQueryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public DeliveryQueryService(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository
    ) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }


    @Transactional(readOnly = true)
    public Page<DeliveryResponse> getDeliveries(
            String status,
            UUID endpointId,
            Pageable pageable
    ) {
        return deliveryRepository
                .findDeliveries(status, endpointId, pageable)
                .map(DeliveryResponse::from);
    }

    @Transactional(readOnly = true)
    public DeliveryDetailResponse getDeliveryById(UUID id) {

        Delivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Delivery not found: " + id
                ));

        List<DeliveryAttemptResponse> attempts =
                deliveryAttemptRepository
                        .findByDeliveryIdOrderByAttemptNoAsc(id)
                        .stream()
                        .map(DeliveryAttemptResponse::from)
                        .toList();

        return new DeliveryDetailResponse(
                DeliveryResponse.from(delivery),
                attempts
        );
    }

}