
package com.example.webhook_relay;

import com.example.webhook_relay.dto.DeliveryResult;
import com.example.webhook_relay.service.DeliveryClaimService;
import com.example.webhook_relay.service.DeliveryClaimService.ClaimedDelivery;
import com.example.webhook_relay.service.DeliveryResultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryWorkerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryResultService resultService;

    private final List<UUID> deliveryIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> endpointIds = new ArrayList<>();

    private UUID createDelivery() {
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
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """,
                deliveryId,
                eventId,
                endpointId
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
    void concurrentWorkersShouldNotClaimSameDelivery() throws Exception {
        // Arrange: Create 10 eligible deliveries.
        Set<UUID> expectedIds = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            expectedIds.add(createDelivery());
        }

        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<List<ClaimedDelivery>>> futures = new ArrayList<>();

        try {
            // Act: Start multiple workers at approximately the same time.
            for (int i = 0; i < workerCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start signal timed out");
                    }

                    return claimService.claimDeliveries();
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready"
            );

            start.countDown();

            List<UUID> claimedIds = new ArrayList<>();

            for (Future<List<ClaimedDelivery>> future : futures) {
                for (ClaimedDelivery delivery :
                        future.get(15, TimeUnit.SECONDS)) {
                    claimedIds.add(delivery.id());
                }
            }

            // Assert: No delivery is claimed more than once.
            assertEquals(
                    claimedIds.size(),
                    new HashSet<>(claimedIds).size(),
                    "A delivery was claimed by multiple workers"
            );

            // All test deliveries should have been claimed.
            assertEquals(expectedIds, new HashSet<>(claimedIds));

            for (UUID id : expectedIds) {
                Integer attemptCount = jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM deliveries WHERE id = ?",
                        Integer.class,
                        id
                );

                assertEquals(1, attemptCount);
            }

        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(20, TimeUnit.SECONDS),
                    "Worker threads did not terminate"
            );
        }
    }

    @Test
    void expiredLeaseShouldBeReclaimedAndStaleResultRejected() {
        // Arrange: Create and claim a delivery.
        UUID deliveryId = createDelivery();

        ClaimedDelivery firstClaim = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(1, firstClaim.attemptNo());

        // Simulate lease expiration.
        jdbcTemplate.update("""
            UPDATE deliveries
            SET locked_until = ?
            WHERE id = ?
            """,
                Timestamp.from(Instant.now().minusSeconds(60)),
                deliveryId
        );

        // Act: Another worker reclaims the expired delivery.
        ClaimedDelivery secondClaim = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(2, secondClaim.attemptNo());
        assertNotEquals(
                firstClaim.lockedUntil(),
                secondClaim.lockedUntil()
        );

        DeliveryResult success = new DeliveryResult(200, null, 25L);

        // Old worker tries to save its result.
        boolean staleAccepted = resultService.saveResult(
                deliveryId,
                firstClaim.attemptNo(),
                firstClaim.lockedUntil(),
                success
        );

        assertFalse(
                staleAccepted,
                "Stale worker result should be rejected"
        );

        // Current worker saves its result.
        boolean currentAccepted = resultService.saveResult(
                deliveryId,
                secondClaim.attemptNo(),
                secondClaim.lockedUntil(),
                success
        );

        assertTrue(currentAccepted);

        // Assert: The delivery is successfully completed.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        assertEquals("SUCCEEDED", status);

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals(2, attemptCount);

        Integer savedAttempts = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_attempts
            WHERE delivery_id = ?
            """,
                Integer.class,
                deliveryId
        );

        // Rejected stale results must not create attempt records.
        assertEquals(1, savedAttempts);

        Integer savedAttemptNo = jdbcTemplate.queryForObject("""
            SELECT attempt_no
            FROM delivery_attempts
            WHERE delivery_id = ?
            """,
                Integer.class,
                deliveryId
        );

        assertEquals(2, savedAttemptNo);
    }

    @Test
    void http400ShouldBeMarkedDeadImmediately() {
        // Arrange
        UUID deliveryId = createDelivery();

        ClaimedDelivery claimed = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        DeliveryResult failure = new DeliveryResult(
                400,
                "Bad Request",
                25L
        );

        // Act
        boolean saved = resultService.saveResult(
                deliveryId,
                claimed.attemptNo(),
                claimed.lockedUntil(),
                failure
        );

        // Assert
        assertTrue(saved);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        assertEquals("DEAD", status);

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals(1, attemptCount);
    }

    @Test
    void http500ShouldScheduleRetry() {
        // Arrange
        UUID deliveryId = createDelivery();

        ClaimedDelivery claimed = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        DeliveryResult failure = new DeliveryResult(
                500,
                "Internal Server Error",
                25L
        );

        Instant beforeSave = Instant.now();

        // Act
        boolean saved = resultService.saveResult(
                deliveryId,
                claimed.attemptNo(),
                claimed.lockedUntil(),
                failure
        );

        Instant afterSave = Instant.now();

        // Assert
        assertTrue(saved);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        assertEquals("PENDING", status);

        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM deliveries WHERE id = ?",
                Timestamp.class,
                deliveryId
        ).toInstant();

        // First retry: full jitter between 0 and 1 second.
        assertFalse(nextAttemptAt.isBefore(beforeSave));
        assertFalse(nextAttemptAt.isAfter(afterSave.plusSeconds(1)));

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals(1, attemptCount);
    }

    @Test
    void http500AtMaxAttemptsShouldBeDead() {
        // Arrange
        UUID deliveryId = createDelivery();

        // Simulate four previous attempts.
        jdbcTemplate.update("""
        UPDATE deliveries
        SET attempt_count = 4
        WHERE id = ?
        """, deliveryId);

        // Claiming increments attempt_count to 5.
        ClaimedDelivery claimed = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(5, claimed.attemptNo());

        DeliveryResult failure = new DeliveryResult(
                500,
                "Internal Server Error",
                25L
        );

        // Act
        boolean saved = resultService.saveResult(
                deliveryId,
                claimed.attemptNo(),
                claimed.lockedUntil(),
                failure
        );

        // Assert
        assertTrue(saved);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        assertEquals("DEAD", status);

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );

        assertEquals(5, attemptCount);

        // A terminal delivery must not be claimed again.
        boolean claimedAgain = claimService.claimDeliveries()
                .stream()
                .anyMatch(d -> d.id().equals(deliveryId));

        assertFalse(claimedAgain);
    }

    @Test
    void expiredLeaseAtMaxAttemptsShouldBeDead() {
        // Arrange
        UUID deliveryId = createDelivery();

        // Simulate four previous attempts.
        jdbcTemplate.update("""
        UPDATE deliveries
        SET attempt_count = 4
        WHERE id = ?
        """, deliveryId);

        ClaimedDelivery claimed = claimService.claimDeliveries()
                .stream()
                .filter(d -> d.id().equals(deliveryId))
                .findFirst()
                .orElseThrow();

        assertEquals(5, claimed.attemptNo());

        // Simulate a worker crash and lease expiration.
        jdbcTemplate.update("""
        UPDATE deliveries
        SET locked_until = ?
        WHERE id = ?
        """,
                Timestamp.from(Instant.now().minusSeconds(60)),
                deliveryId
        );

        // Act
        int cleaned = claimService.cleanupExpiredDeliveries();

        // Assert
        assertEquals(1, cleaned);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?",
                String.class,
                deliveryId
        );

        assertEquals("DEAD", status);

        Timestamp lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM deliveries WHERE id = ?",
                Timestamp.class,
                deliveryId
        );

        assertNull(lockedUntil);

        // The delivery must not be reclaimed.
        boolean claimedAgain = claimService.claimDeliveries()
                .stream()
                .anyMatch(d -> d.id().equals(deliveryId));

        assertFalse(claimedAgain);
    }
    
}
