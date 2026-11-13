
package com.example.webhook_relay;

import com.example.webhook_relay.ratelimit.TokenBucketRateLimiter;
import com.example.webhook_relay.service.DeliveryClaimService;
import com.example.webhook_relay.service.DeliveryResultService;
import com.example.webhook_relay.service.DeliverySender;
import com.example.webhook_relay.service.DeliveryWorker;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRateLimitHttpIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryResultService resultService;

    @Autowired
    private DeliverySender deliverySender;

    private HttpServer server;

    private UUID endpointId;
    private final List<UUID> eventIds = new ArrayList<>();

    private final List<UUID> deliveryIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (server != null) {
            server.stop(0);
        }

        for (UUID id : deliveryIds) {
            jdbcTemplate.update(
                    "DELETE FROM delivery_attempts WHERE delivery_id = ?",
                    id
            );
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE id = ?",
                    id
            );
        }

        for (UUID id : eventIds) {
            jdbcTemplate.update(
                    "DELETE FROM events WHERE id = ?",
                    id
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
    void shouldRateLimitTenDeliveriesToTwoPerSecond() throws Exception {

        List<Long> receivedAt = new CopyOnWriteArrayList<>();

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/webhook", exchange -> {
            receivedAt.add(System.nanoTime());

            exchange.getRequestBody().readAllBytes();

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();

        String url = "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/webhook";

        endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO endpoints
                (id, url, secret, event_types, rate_limit_per_sec)
            VALUES (?, ?, ?, ARRAY['order.created']::text[], ?)
            """,
                endpointId,
                url,
                "test-secret",
                2
        );

        for (int i = 0; i < 10; i++) {
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            eventIds.add(eventId);
            deliveryIds.add(deliveryId);

            jdbcTemplate.update("""
        INSERT INTO events (id, event_type, payload)
        VALUES (?, ?, ?::jsonb)
        """,
                    eventId,
                    "order.created",
                    "{\"orderId\":" + i + "}"
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

        DeliveryWorker worker = new DeliveryWorker(
                claimService,
                deliverySender,
                resultService,
                new TokenBucketRateLimiter()
        );

        long deadline = System.nanoTime()
                + Duration.ofSeconds(15).toNanos();

        int succeeded = 0;

        while (System.nanoTime() < deadline) {
            worker.processDeliveries();

            succeeded = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM deliveries
        WHERE endpoint_id = ?
          AND status = 'SUCCEEDED'
        """,
                    Integer.class,
                    endpointId
            );

            if (succeeded == 10) {
                break;
            }

            Thread.sleep(100);
        }

        assertEquals(
                10,
                succeeded,
                "Expected 10 succeeded deliveries for test endpoint"
        );

        assertEquals(
                10,
                receivedAt.size(),
                "Expected exactly 10 HTTP requests"
        );

        List<Long> times = new ArrayList<>(receivedAt);
        times.sort(Long::compareTo);

        double totalSeconds = (times.get(9) - times.get(0))
                / 1_000_000_000.0;

        assertTrue(
                totalSeconds >= 3.5,
                "Requests were sent too quickly: "
                        + totalSeconds + " seconds"
        );



        for (UUID id : deliveryIds) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM deliveries WHERE id = ?",
                    String.class,
                    id
            );

            Integer attemptCount = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM deliveries WHERE id = ?",
                    Integer.class,
                    id
            );

            Integer savedAttempts = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM delivery_attempts
                    WHERE delivery_id = ?
                    """,
                    Integer.class,
                    id
            );

            assertEquals("SUCCEEDED", status);
            assertEquals(1, attemptCount);
            assertEquals(1, savedAttempts);
        }
    }
}
