package com.example.webhook_relay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.repository.EndpointRepository;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.EventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.repository.DeliveryRepository;

import com.example.webhook_relay.entity.DeliveryAttempt;
import com.example.webhook_relay.repository.DeliveryAttemptRepository;
import java.time.Instant;

@SpringBootTest
@Testcontainers
class RepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void contextLoads() {
    }
    @Autowired
    private EndpointRepository endpointRepository;

    // Test method (edited)
    @Test
    @Transactional
    void shouldSaveDeliveryAttempt() {

        // Arrange
        Endpoint endpoint = endpointRepository.saveAndFlush(
                new Endpoint(
                        "https://example.com/webhook",
                        "test-secret",
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

        Instant attemptedAt = Instant.now();

        DeliveryAttempt attempt = new DeliveryAttempt(
                delivery,
                1,
                200,
                null,
                150L,
                attemptedAt
        );

        // Act
        DeliveryAttempt saved =
                deliveryAttemptRepository.saveAndFlush(attempt);

        UUID savedId = saved.getId();

        entityManager.clear();

        DeliveryAttempt found = deliveryAttemptRepository
                .findById(savedId)
                .orElseThrow();

        // Assert
        assertNotNull(savedId);
        assertEquals(delivery.getId(), found.getDelivery().getId());
        assertEquals(1, found.getAttemptNo());
        assertEquals(200, found.getStatusCode());
        assertNull(found.getError());
        assertEquals(150L, found.getDurationMs());
        assertEquals(attemptedAt, found.getAttemptedAt());
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;
}