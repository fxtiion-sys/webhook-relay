
package com.example.webhook_relay.service;

import com.example.webhook_relay.repository.DeliveryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Profile("local")
public class LocalDemoSecretResolver {

    private final DeliveryRepository deliveryRepository;

    public LocalDemoSecretResolver(
            DeliveryRepository deliveryRepository
    ) {
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> resolve(String webhookId) {
        try {
            UUID deliveryId = UUID.fromString(webhookId);

            return deliveryRepository.findById(deliveryId)
                    .map(delivery ->
                            delivery.getEndpoint().getSecret()
                    );
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
