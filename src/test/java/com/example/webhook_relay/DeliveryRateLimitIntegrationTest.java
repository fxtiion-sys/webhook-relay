
package com.example.webhook_relay;

import com.example.webhook_relay.service.DeliveryClaimService;
import com.example.webhook_relay.service.DeliveryClaimService.ClaimedDelivery;
import com.example.webhook_relay.service.DeliveryResultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRateLimitIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryResultService resultService;

    private UUID endpointId;
    private UUID eventId;
    private UUID deliveryId;

    private void createDelivery(int rateLimitPerSec) {
        endpointId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO endpoints
                (id, url, secret, event_types, rate_limit_per_sec)
            VALUES (?, ?, ?, ARRAY['order.created']::text[], ?)
            """,
                endpointId,
                "http://localhost:8080/mock-receiver",
                "test-secret",
                rateLimitPerSec
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
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """,
                deliveryId,
                eventId,
                endpointId
        );
    }

    @AfterEach
    void cleanUp() {
        if (deliveryId != null) {
            jdbcTemplate.update(
                    "DELETE FROM delivery_attempts WHERE delivery_id = ?",
                    deliveryId
            );
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?",
                    deliveryId
            );
        }

        if (eventId != null) {
            jdbcTemplate.update(
                    "DELETE FROM events WHERE id = ?",
                    eventId
            );
        }

        if (endpointId != null) {
            jdbcTemplate.update(
                    "DELETE FROM endpoints WHERE id = ?",
                    endpointId
            );
        }
    }

    @Test
    void rateLimitedDeliveryShouldBeDeferredWithoutConsumingAttempt() {
        createDelivery(2);

        ClaimedDelivery claimed = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(endpointId, claimed.endpointId());
        assertEquals(2, claimed.rateLimitPerSec());
        assertEquals(1, claimed.attemptNo());

        Instant beforeDefer = Instant.now();

        boolean deferred = resultService.deferRateLimitedDelivery(
                claimed.id(),
                claimed.attemptNo(),
                claimed.lockedUntil(),
                Duration.ofMillis(500)
        );

        assertTrue(deferred);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        java.sql.Timestamp lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM deliveries WHERE id = ?",
                java.sql.Timestamp.class,
                deliveryId
        );

        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM deliveries WHERE id = ?",
                java.sql.Timestamp.class,
                deliveryId
        ).toInstant();

        Integer savedAttempts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts WHERE delivery_id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals("PENDING", status);
        assertEquals(0, attemptCount);
        assertNull(lockedUntil);
        assertEquals(0, savedAttempts);
        assertTrue(nextAttemptAt.isAfter(beforeDefer));
    }

    @Test
    void staleWorkerShouldNotDeferReclaimedDelivery() {
        createDelivery(2);

        ClaimedDelivery firstClaim = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        jdbcTemplate.update("""
            UPDATE deliveries
            SET locked_until = ?
            WHERE id = ?
            """,
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                deliveryId
        );

        ClaimedDelivery secondClaim = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(2, secondClaim.attemptNo());

        boolean staleDeferred = resultService.deferRateLimitedDelivery(
                firstClaim.id(),
                firstClaim.attemptNo(),
                firstClaim.lockedUntil(),
                Duration.ofMillis(500)
        );

        assertFalse(staleDeferred);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals("IN_PROGRESS", status);
        assertEquals(2, attemptCount);
    }
}
