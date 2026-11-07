
package com.example.webhook_relay;

import com.example.webhook_relay.dto.DeliveryResponse;
import com.example.webhook_relay.service.DeliveryQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import java.util.List;

import com.example.webhook_relay.dto.DeliveryDetailResponse;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;


class DeliveryQueryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DeliveryQueryService deliveryQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyPageWhenNoStatusMatches() {

        String status = "NON_EXISTENT_STATUS_" + UUID.randomUUID();

        Page<DeliveryResponse> result =
                deliveryQueryService.getDeliveries(
                        status,
                        null,
                        PageRequest.of(0, 10)
                );

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
    }


    private final List<UUID> deliveryIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> endpointIds = new ArrayList<>();

    private UUID createDelivery(String status) {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
        INSERT INTO endpoints (id, url, secret, event_types)
        VALUES (?, ?, ?, ARRAY['order.created']::text[])
        """,
                endpointId,
                "http://localhost:8080/mock-receiver",
                "test-secret"
        );

        jdbcTemplate.update("""
        INSERT INTO events (id, event_type, payload)
        VALUES (?, ?, ?::jsonb)
        """,
                eventId,
                "order.created",
                "{\"orderId\":123}"
        );

        jdbcTemplate.update("""
        INSERT INTO deliveries (id, event_id, endpoint_id, status)
        VALUES (?, ?, ?, ?)
        """,
                deliveryId,
                eventId,
                endpointId,
                status
        );

        endpointIds.add(endpointId);
        eventIds.add(eventId);
        deliveryIds.add(deliveryId);

        return deliveryId;
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : deliveryIds) {
            jdbcTemplate.update(
                    "DELETE FROM delivery_attempts WHERE delivery_id = ?", id
            );
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?", id
            );
        }

        for (UUID id : eventIds) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", id);
        }

        for (UUID id : endpointIds) {
            jdbcTemplate.update("DELETE FROM endpoints WHERE id = ?", id);
        }

        deliveryIds.clear();
        eventIds.clear();
        endpointIds.clear();
    }


    @Test
    void shouldFilterDeliveriesByStatus() {
        // Arrange
        UUID succeededId = createDelivery("SUCCEEDED");
        createDelivery("DEAD");

        // Act
        Page<DeliveryResponse> result =
                deliveryQueryService.getDeliveries(
                        "SUCCEEDED",
                        null,
                        PageRequest.of(0, 10)
                );

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(succeededId, result.getContent().get(0).id());
        assertEquals("SUCCEEDED", result.getContent().get(0).status());
    }


    @Test
    void shouldFilterDeliveriesByEndpointId() {
        // Arrange
        UUID targetDeliveryId = createDelivery("SUCCEEDED");
        UUID otherDeliveryId = createDelivery("DEAD");

        UUID targetEndpointId = jdbcTemplate.queryForObject(
                "SELECT endpoint_id FROM deliveries WHERE id = ?",
                UUID.class,
                targetDeliveryId
        );

        // Act
        Page<DeliveryResponse> result =
                deliveryQueryService.getDeliveries(
                        null,
                        targetEndpointId,
                        PageRequest.of(0, 10)
                );

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(targetDeliveryId, result.getContent().get(0).id());
        assertEquals(targetEndpointId, result.getContent().get(0).endpointId());
    }



    @Test
    void shouldPaginateDeliveries() {
        // Arrange: Create 5 deliveries with a valid status.
        List<UUID> createdIds = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            createdIds.add(createDelivery("PENDING"));
        }

        // Act: Query deliveries using pages of size 2.
        Page<DeliveryResponse> firstPage =
                deliveryQueryService.getDeliveries(
                        "PENDING",
                        null,
                        PageRequest.of(0, 2)
                );

        Page<DeliveryResponse> secondPage =
                deliveryQueryService.getDeliveries(
                        "PENDING",
                        null,
                        PageRequest.of(1, 2)
                );

        Page<DeliveryResponse> thirdPage =
                deliveryQueryService.getDeliveries(
                        "PENDING",
                        null,
                        PageRequest.of(2, 2)
                );

        // Assert: Verify pagination metadata.
        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());

        assertEquals(2, firstPage.getNumberOfElements());
        assertEquals(2, secondPage.getNumberOfElements());
        assertEquals(1, thirdPage.getNumberOfElements());

        assertTrue(firstPage.isFirst());
        assertTrue(thirdPage.isLast());
    }


    @Test
    void shouldReturnDeliveryDetails() {
        // Arrange: Create a delivery.
        UUID deliveryId = createDelivery("SUCCEEDED");

        // Act: Retrieve delivery details.
        DeliveryDetailResponse result =
                deliveryQueryService.getDeliveryById(deliveryId);

        // Assert: Verify delivery information.
        assertNotNull(result);
        assertEquals(deliveryId, result.delivery().id());
        assertEquals("SUCCEEDED", result.delivery().status());

        // No attempts have been created yet.
        assertNotNull(result.attempts());
        assertTrue(result.attempts().isEmpty());
    }


    @Test
    void shouldReturnAttemptsInAscendingOrder() {
        // Arrange: Create a delivery.
        UUID deliveryId = createDelivery("SUCCEEDED");

        // Insert attempt #2 first.
        jdbcTemplate.update("""
        INSERT INTO delivery_attempts
            (delivery_id, attempt_no, status_code,
             error, duration_ms, attempted_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
                deliveryId,
                2,
                200,
                null,
                75L,
                Timestamp.from(Instant.now())
        );

        // Insert attempt #1 second.
        jdbcTemplate.update("""
        INSERT INTO delivery_attempts
            (delivery_id, attempt_no, status_code,
             error, duration_ms, attempted_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
                deliveryId,
                1,
                500,
                "Internal Server Error",
                120L,
                Timestamp.from(Instant.now().minusSeconds(10))
        );

        // Act: Retrieve delivery details.
        DeliveryDetailResponse result =
                deliveryQueryService.getDeliveryById(deliveryId);

        // Assert: Both attempts are returned.
        assertEquals(2, result.attempts().size());

        // Verify ascending order, regardless of insertion order.
        assertEquals(1, result.attempts().get(0).attemptNo());
        assertEquals(2, result.attempts().get(1).attemptNo());

        // Verify attempt details.
        assertEquals(500, result.attempts().get(0).statusCode());
        assertEquals("Internal Server Error",
                result.attempts().get(0).error());

        assertEquals(200, result.attempts().get(1).statusCode());
        assertNull(result.attempts().get(1).error());
    }


    @Test
    void shouldThrowNotFoundForMissingDelivery() {
        // Arrange: Generate an ID that does not exist.
        UUID missingId = UUID.randomUUID();

        // Act: Try to retrieve the delivery.
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> deliveryQueryService.getDeliveryById(missingId)
        );

        // Assert: Verify the exception status.
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Delivery not found"));
    }

    @Test
    void shouldReturnOnlyDeadDeliveries() {
        // Arrange
        UUID deadId = createDelivery("DEAD");
        createDelivery("SUCCEEDED");

        // Act
        Page<DeliveryResponse> result =
                deliveryQueryService.getDeliveries(
                        "DEAD",
                        null,
                        PageRequest.of(0, 10)
                );

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(deadId, result.getContent().get(0).id());
        assertEquals("DEAD", result.getContent().get(0).status());
    }

    @Test
    void shouldReturnDeadDeliveryWithAttemptHistory() {
        // Arrange
        UUID deliveryId = createDelivery("DEAD");

        jdbcTemplate.update("""
        INSERT INTO delivery_attempts
            (delivery_id, attempt_no, status_code,
             error, duration_ms, attempted_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
                deliveryId,
                1,
                500,
                "Internal Server Error",
                120L,
                Timestamp.from(Instant.now().minusSeconds(10))
        );

        jdbcTemplate.update("""
        INSERT INTO delivery_attempts
            (delivery_id, attempt_no, status_code,
             error, duration_ms, attempted_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
                deliveryId,
                2,
                500,
                "Internal Server Error",
                95L,
                Timestamp.from(Instant.now())
        );

        // Act
        DeliveryDetailResponse result =
                deliveryQueryService.getDeliveryById(deliveryId);

        // Assert
        assertEquals("DEAD", result.delivery().status());
        assertEquals(deliveryId, result.delivery().id());

        assertEquals(2, result.attempts().size());

        assertEquals(1, result.attempts().get(0).attemptNo());
        assertEquals(500, result.attempts().get(0).statusCode());

        assertEquals(2, result.attempts().get(1).attemptNo());
        assertEquals(500, result.attempts().get(1).statusCode());

        assertEquals(
                "Internal Server Error",
                result.attempts().get(1).error()
        );
    }
    
}
