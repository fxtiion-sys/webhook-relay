package com.example.webhook_relay;

import com.example.webhook_relay.service.DeliveryReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import com.example.webhook_relay.exception.DeliveryReplayConflictException;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.webhook_relay.service.DeliveryClaimService;

import com.example.webhook_relay.service.DeliveryResultService;
import com.example.webhook_relay.dto.DeliveryResult;


@AutoConfigureMockMvc
class DeliveryReplayIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryReplayService replayService;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryResultService resultService;

    @Test
    void deadDeliveryShouldBecomePendingAfterReplay() throws Exception{

        // Arrange
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        try {
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
                INSERT INTO deliveries (
                    id, event_id, endpoint_id,
                    status, attempt_count
                )
                VALUES (?, ?, ?, 'DEAD', 5)
                """,
                    deliveryId,
                    eventId,
                    endpointId
            );

            // Act
            mockMvc.perform(
                            post("/deliveries/{id}/replay", deliveryId)
                    )
                    .andExpect(status().isAccepted());

            // Assert
            var result = jdbcTemplate.queryForMap("""
                SELECT id, status, attempt_count, attempt_base
                FROM deliveries
                WHERE id = ?
                """,
                    deliveryId
            );

            assertEquals(deliveryId, result.get("id"));
            assertEquals("PENDING", result.get("status"));
            assertEquals(5, ((Number) result.get("attempt_count")).intValue());
            assertEquals(5, ((Number) result.get("attempt_base")).intValue());

        } finally {
            // Cleanup
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?", deliveryId
            );
            jdbcTemplate.update(
                    "DELETE FROM events WHERE id = ?", eventId
            );
            jdbcTemplate.update(
                    "DELETE FROM endpoints WHERE id = ?", endpointId
            );
        }
    }

    @Test
    void replayNonexistentDeliveryShouldThrowNotFound() {

        UUID nonexistentId = UUID.randomUUID();

        assertThrows(
                java.util.NoSuchElementException.class,
                () -> replayService.replay(nonexistentId)
        );
    }

    @Test
    void replayPendingDeliveryShouldThrowConflict() throws Exception {

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        try {
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
            INSERT INTO deliveries (
                id, event_id, endpoint_id,
                status, attempt_count
            )
            VALUES (?, ?, ?, 'PENDING', 0)
            """,
                    deliveryId,
                    eventId,
                    endpointId
            );

            assertThrows(
                    DeliveryReplayConflictException.class,
                    () -> replayService.replay(deliveryId)
            );

            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM deliveries WHERE id = ?",
                    String.class,
                    deliveryId
            );

            assertEquals("PENDING", status);

            mockMvc.perform(
                            post("/deliveries/{id}/replay", deliveryId)
                    )
                    .andExpect(status().isConflict());

        } finally {
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?", deliveryId
            );
            jdbcTemplate.update(
                    "DELETE FROM events WHERE id = ?", eventId
            );
            jdbcTemplate.update(
                    "DELETE FROM endpoints WHERE id = ?", endpointId
            );
        }
    }

    @Test
    void replayNonexistentDeliveryShouldReturn404() throws Exception {

        UUID nonexistentId = UUID.randomUUID();

        mockMvc.perform(
                        post("/deliveries/{id}/replay", nonexistentId)
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void replayShouldPreserveExistingAttemptHistory() {

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        try {
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
            INSERT INTO deliveries (
                id, event_id, endpoint_id,
                status, attempt_count
            )
            VALUES (?, ?, ?, 'DEAD', 3)
            """,
                    deliveryId,
                    eventId,
                    endpointId
            );

            // Arrange: Insert three existing delivery attempt records.
            for (int attemptNo = 1; attemptNo <= 3; attemptNo++) {
                jdbcTemplate.update("""
                INSERT INTO delivery_attempts (
                    id, delivery_id, attempt_no,
                    status_code, duration_ms, attempted_at
                )
                VALUES (?, ?, ?, 500, 100, now())
                """,
                        UUID.randomUUID(),
                        deliveryId,
                        attemptNo
                );
            }

            // Act
            replayService.replay(deliveryId);

            // Claim the replayed delivery for a new retry cycle.
            var claimedDeliveries = claimService.claimDeliveries();

            var claimedDelivery = claimedDeliveries.stream()
                    .filter(delivery -> delivery.id().equals(deliveryId))
                    .findFirst()
                    .orElseThrow();

            assertEquals(4, claimedDelivery.attemptNo());

            // Record a successful delivery result after replay.
            DeliveryResult successResult = new DeliveryResult(
                    200,
                    null,
                    120L
            );

            boolean saved = resultService.saveResult(
                    deliveryId,
                    claimedDelivery.attemptNo(),
                    claimedDelivery.lockedUntil(),
                    successResult
            );

            assertTrue(saved);

            // Assert: Verify that existing delivery attempt records are preserved.
            List<Integer> attemptNumbers = jdbcTemplate.queryForList(
                    """
                    SELECT attempt_no
                    FROM delivery_attempts
                    WHERE delivery_id = ?
                    ORDER BY attempt_no
                    """,
                    Integer.class,
                    deliveryId
            );

            // Verify that the new attempt is appended to the existing history.
            assertEquals(List.of(1, 2, 3, 4), attemptNumbers);

            var delivery = jdbcTemplate.queryForMap("""
            SELECT status, attempt_count, attempt_base
            FROM deliveries
            WHERE id = ?
            """,
                    deliveryId
            );

            // Verify the final delivery state.
            assertEquals("SUCCEEDED", delivery.get("status"));
            assertEquals(4, delivery.get("attempt_count"));
            assertEquals(3, delivery.get("attempt_base"));

            // Verify the HTTP status code of the new attempt.
            Integer statusCode = jdbcTemplate.queryForObject("""
                    SELECT status_code
                    FROM delivery_attempts
                    WHERE delivery_id = ? AND attempt_no = 4
                    """,
                    Integer.class,
                    deliveryId
            );

            assertEquals(200, statusCode);

        } finally {
            jdbcTemplate.update(
                    "DELETE FROM delivery_attempts WHERE delivery_id = ?",
                    deliveryId
            );
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?",
                    deliveryId
            );
            jdbcTemplate.update(
                    "DELETE FROM events WHERE id = ?",
                    eventId
            );
            jdbcTemplate.update(
                    "DELETE FROM endpoints WHERE id = ?",
                    endpointId
            );
        }
    }
}