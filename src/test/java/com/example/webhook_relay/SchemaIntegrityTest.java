
package com.example.webhook_relay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIntegrityTest extends PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyFlywayMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version IN ('1', '2')
                  AND success = true
                """,
                Integer.class
        );

        assertEquals(2, count);
    }

    @Test
    void shouldCreateAllFourTables() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'endpoints',
                      'events',
                      'deliveries',
                      'delivery_attempts'
                  )
                """,
                Integer.class
        );

        assertEquals(4, count);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKey() {
        String sql = """
            INSERT INTO events (event_type, payload, idempotency_key)
            VALUES (?, '{}'::jsonb, ?)
            """;

        String key = "duplicate-key-test";

        jdbcTemplate.update(sql, "order.created", key);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, "order.created", key)
        );
    }


    @Test
    void shouldAllowMultipleNullIdempotencyKeys() {
        String sql = """
            INSERT INTO events (event_type, payload, idempotency_key)
            VALUES (?, '{}'::jsonb, NULL)
            """;

        Integer before = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM events
                WHERE idempotency_key IS NULL
                """,
                Integer.class
        );

        jdbcTemplate.update(sql, "order.created");
        jdbcTemplate.update(sql, "order.updated");

        Integer after = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM events
                WHERE idempotency_key IS NULL
                """,
                Integer.class
        );

        assertEquals(before + 2, after);
    }


    @Test
    void shouldRejectDuplicateEventEndpointPair() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        String sql = """
            INSERT INTO deliveries (event_id, endpoint_id)
            VALUES (?, ?)
            """;

        jdbcTemplate.update(sql, eventId, endpointId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, eventId, endpointId)
        );
    }

    @Test
    void shouldRejectDuplicateDeliveryAttemptNumber() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """, deliveryId, eventId, endpointId);

        String sql = """
            INSERT INTO delivery_attempts
                (delivery_id, attempt_no, duration_ms, attempted_at)
            VALUES (?, ?, 100, now())
            """;

        jdbcTemplate.update(sql, deliveryId, 1);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, deliveryId, 1)
        );
    }


    @Test
    void shouldRejectDeliveryWithMissingEvent() {
        UUID missingEventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO deliveries (event_id, endpoint_id)
                    VALUES (?, ?)
                    """, missingEventId, endpointId)
        );
    }

    @Test
    void shouldRejectDeliveryWithMissingEndpoint() {
        UUID eventId = UUID.randomUUID();
        UUID missingEndpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO deliveries (event_id, endpoint_id)
                    VALUES (?, ?)
                    """, eventId, missingEndpointId)
        );
    }

    @Test
    void shouldRejectAttemptWithMissingDelivery() {
        UUID missingDeliveryId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO delivery_attempts
                        (delivery_id, attempt_no, duration_ms, attempted_at)
                    VALUES (?, 1, 100, now())
                    """, missingDeliveryId)
        );
    }

    @Test
    void shouldRejectNonObjectJsonPayload() {
        String sql = """
            INSERT INTO events (event_type, payload)
            VALUES ('order.created', ?::jsonb)
            """;

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, "[1, 2, 3]")
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, "\"hello\"")
        );
    }


    @Test
    void shouldRejectInvalidDeliveryStatus() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO deliveries
                        (event_id, endpoint_id, status)
                    VALUES (?, ?, 'INVALID')
                    """, eventId, endpointId)
        );
    }


    @Test
    void shouldEnforceDeliveryLeaseRule() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        // Invalid: IN_PROGRESS without a lease
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO deliveries
                        (event_id, endpoint_id, status)
                    VALUES (?, ?, 'IN_PROGRESS')
                    """, eventId, endpointId)
        );

        // Invalid: SUCCEEDED with a lease
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                    INSERT INTO deliveries
                        (event_id, endpoint_id, status, locked_until)
                    VALUES (?, ?, 'SUCCEEDED', now() + interval '5 minutes')
                    """, eventId, endpointId)
        );

        // Valid: IN_PROGRESS with a lease
        jdbcTemplate.update("""
            INSERT INTO deliveries
                (event_id, endpoint_id, status, locked_until)
            VALUES (?, ?, 'IN_PROGRESS', now() + interval '5 minutes')
            """, eventId, endpointId);
    }


    @Test
    void shouldRejectInvalidDeliveryNumericValues() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        String sql = """
            INSERT INTO deliveries
                (event_id, endpoint_id, attempt_count, last_status_code)
            VALUES (?, ?, ?, ?)
            """;

        // attempt_count must be >= 0
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, eventId, endpointId, -1, 200)
        );

        // HTTP status codes must be between 100 and 599
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, eventId, endpointId, 0, 99)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, eventId, endpointId, 0, 600)
        );
    }

    @Test
    void shouldRejectInvalidDeliveryAttemptNumericValues() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO events (id, event_type, payload)
            VALUES (?, 'order.created', '{}'::jsonb)
            """, eventId);

        jdbcTemplate.update("""
            INSERT INTO endpoints (id, url, secret, event_types)
            VALUES (?, 'https://example.com/webhook',
                    'secret', ARRAY['order.created'])
            """, endpointId);

        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id)
            VALUES (?, ?, ?)
            """, deliveryId, eventId, endpointId);

        String sql = """
            INSERT INTO delivery_attempts
                (delivery_id, attempt_no, status_code, duration_ms, attempted_at)
            VALUES (?, ?, ?, ?, now())
            """;

        // attempt_no must be positive
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, deliveryId, 0, 200, 100L)
        );

        // status_code must be between 100 and 599
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, deliveryId, 1, 99, 100L)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, deliveryId, 1, 600, 100L)
        );

        // duration_ms must be >= 0
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, deliveryId, 1, 200, -1L)
        );
    }


    @Test
    void shouldCreatePartialIndexes() {
        String pendingIndex = jdbcTemplate.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'deliveries'
                  AND indexname = 'idx_deliveries_pending_due'
                """,
                String.class
        );

        String leaseIndex = jdbcTemplate.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'deliveries'
                  AND indexname = 'idx_deliveries_expired_lease'
                """,
                String.class
        );

        assertNotNull(pendingIndex);
        assertNotNull(leaseIndex);

        assertTrue(pendingIndex.contains(
                "(next_attempt_at, id)"
        ));
        assertTrue(pendingIndex.contains(
                "status = 'PENDING'"
        ));

        assertTrue(leaseIndex.contains(
                "(locked_until, id)"
        ));
        assertTrue(leaseIndex.contains(
                "status = 'IN_PROGRESS'"
        ));
    }


    @Test
    void shouldRejectInvalidEndpointValues() {
        String sql = """
            INSERT INTO endpoints
                (url, secret, event_types, rate_limit_per_sec)
            VALUES (?, ?, ?::text[], ?)
            """;

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "   ", "secret", "{order.created}", 10)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "https://example.com", "   ", "{order.created}", 10)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "https://example.com", "secret", "{}", 10)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "https://example.com", "secret", "{order.created}", 0)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "https://example.com", "secret", "{order.created}", -1)
        );
    }


    @Test
    void shouldRejectBlankEventValues() {
        String sql = """
            INSERT INTO events
                (event_type, payload, idempotency_key)
            VALUES (?, '{}'::jsonb, ?)
            """;

        // event_type cannot be blank
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "", "key-1")
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "   ", "key-2")
        );

        // idempotency_key can be NULL, but not blank
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "order.created", "")
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        sql, "order.created", "   ")
        );
    }


}
