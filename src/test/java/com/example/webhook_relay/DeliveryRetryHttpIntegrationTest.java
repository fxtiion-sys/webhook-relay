
package com.example.webhook_relay;

import com.example.webhook_relay.ratelimit.TokenBucketRateLimiter;
import com.example.webhook_relay.service.DeliveryClaimService;
import com.example.webhook_relay.service.DeliveryResultService;
import com.example.webhook_relay.service.DeliverySender;
import com.example.webhook_relay.service.DeliveryWorker;
import com.github.tomakehurst.wiremock.WireMockServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryRetryHttpIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryResultService resultService;

    @Autowired
    private DeliverySender deliverySender;

    private WireMockServer wireMockServer;

    private UUID endpointId;
    private UUID eventId;
    private UUID deliveryId;

    @AfterEach
    void cleanUp() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }

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
    void shouldRetryTwiceThenSucceed() {
        // Arrange: WireMock returns 500, 500, then 200.
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second-attempt"));

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .inScenario("retry")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("third-attempt"));

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .inScenario("retry")
                .whenScenarioStateIs("third-attempt")
                .willReturn(aResponse().withStatus(200)));

        endpointId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO endpoints (id, url, secret, event_types)
                VALUES (?, ?, ?, ARRAY['order.created']::text[])
                """,
                endpointId,
                wireMockServer.baseUrl() + "/webhook",
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

        DeliveryWorker worker = new DeliveryWorker(
                claimService,
                deliverySender,
                resultService,
                new TokenBucketRateLimiter()
        );

        // Act: Process deliveries until retry succeeds.
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    worker.processDeliveries();

                    String status = jdbcTemplate.queryForObject(
                            "SELECT status FROM deliveries WHERE id = ?",
                            String.class,
                            deliveryId
                    );

                    return "SUCCEEDED".equals(status);
                });

        // Assert: Exactly three HTTP requests were sent.
        wireMockServer.verify(
                3,
                postRequestedFor(urlEqualTo("/webhook"))
        );

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

        Integer savedAttempts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM delivery_attempts
                WHERE delivery_id = ?
                """,
                Integer.class,
                deliveryId
        );

        assertEquals("SUCCEEDED", status);
        assertEquals(3, attemptCount);
        assertEquals(3, savedAttempts);
    }

    @Test
    void shouldRetryAfterTimeoutThenSucceed() {
        // Arrange: First request times out, second succeeds.
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .inScenario("timeout-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000))
                .willSetStateTo("retry-success"));

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .inScenario("timeout-retry")
                .whenScenarioStateIs("retry-success")
                .willReturn(aResponse().withStatus(200)));

        endpointId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, ?, ?, ARRAY['order.created']::text[])
            """,
                endpointId,
                wireMockServer.baseUrl() + "/webhook",
                "test-secret"
        );

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, ?, ?::jsonb)
            """,
                eventId,
                "order.created",
                "{\"orderId\":456}"
        );

        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """,
                deliveryId,
                eventId,
                endpointId
        );

        DeliveryWorker worker = new DeliveryWorker(
                claimService,
                deliverySender,
                resultService,
                new TokenBucketRateLimiter()
        );

        // Act: Process until the retry succeeds.
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    worker.processDeliveries();

                    String status = jdbcTemplate.queryForObject(
                            "SELECT status FROM deliveries WHERE id = ?",
                            String.class,
                            deliveryId
                    );

                    return "SUCCEEDED".equals(status);
                });

        // Assert: Exactly two HTTP requests.
        wireMockServer.verify(
                2,
                postRequestedFor(urlEqualTo("/webhook"))
        );

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

        Integer savedAttempts = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_attempts
            WHERE delivery_id = ?
            """,
                Integer.class,
                deliveryId
        );

        assertEquals("SUCCEEDED", status);
        assertEquals(2, attemptCount);
        assertEquals(2, savedAttempts);
    }


    @Test
    void shouldMarkDeliveryDeadImmediatelyOnHttp400() {
        // Arrange: Receiver always responds with HTTP 400.
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(400)));

        endpointId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, ?, ?, ARRAY['order.created']::text[])
            """,
                endpointId,
                wireMockServer.baseUrl() + "/webhook",
                "test-secret"
        );

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, ?, ?::jsonb)
            """,
                eventId,
                "order.created",
                "{\"orderId\":789}"
        );

        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """,
                deliveryId,
                eventId,
                endpointId
        );

        DeliveryWorker worker = new DeliveryWorker(
                claimService,
                deliverySender,
                resultService,
                new TokenBucketRateLimiter()
        );

        // Act: Process the delivery once.
        worker.processDeliveries();

        // Assert: The delivery becomes DEAD without retry.
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

        Integer savedAttempts = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM delivery_attempts
            WHERE delivery_id = ?
            """,
                Integer.class,
                deliveryId
        );

        Integer savedStatusCode = jdbcTemplate.queryForObject("""
            SELECT status_code
            FROM delivery_attempts
            WHERE delivery_id = ?
            """,
                Integer.class,
                deliveryId
        );

        assertEquals("DEAD", status);
        assertEquals(1, attemptCount);
        assertEquals(1, savedAttempts);
        assertEquals(400, savedStatusCode);

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/webhook"))
        );

        // A terminal delivery must not be processed again.
        worker.processDeliveries();

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/webhook"))
        );
    }


}
