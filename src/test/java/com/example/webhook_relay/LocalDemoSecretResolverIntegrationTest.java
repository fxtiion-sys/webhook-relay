
package com.example.webhook_relay;

import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;
import com.example.webhook_relay.service.LocalDemoSecretResolver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("local")
@TestPropertySource(properties = {
        "server.port=0",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/placeholder",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "app.worker.enabled=false"
})
@Transactional
class LocalDemoSecretResolverIntegrationTest
        extends PostgresIntegrationTest {

    @Autowired
    private LocalDemoSecretResolver resolver;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void existingDeliveryResolvesEndpointSecret() throws Exception {
        // Arrange: create an endpoint with a unique HMAC secret.
        String secret = "integration-test-secret";

        Endpoint endpoint = endpointRepository.saveAndFlush(
                new Endpoint(
                        "https://example.com/webhook",
                        secret,
                        new String[]{"order.created"},
                        10
                )
        );

        Event event = eventRepository.saveAndFlush(
                new Event(
                        "order.created",
                        objectMapper.readTree("{\"orderId\":123}")
                )
        );

        Delivery delivery = deliveryRepository.saveAndFlush(
                new Delivery(event, endpoint)
        );

        // Act: resolve the secret using the delivery ID.
        String resolvedSecret = resolver
                .resolve(delivery.getId().toString())
                .orElseThrow();

        // Assert: the secret belongs to the delivery's endpoint.
        assertEquals(secret, resolvedSecret);
    }

    @Test
    void unknownDeliveryReturnsEmpty() {
        String unknownId = UUID.randomUUID().toString();

        assertTrue(resolver.resolve(unknownId).isEmpty());
    }

    @Test
    void invalidUuidReturnsEmpty() {
        assertTrue(resolver.resolve("not-a-uuid").isEmpty());
    }
}
