
package com.example.webhook_relay.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.webhook_relay.retry.RetryPolicy;

@Service
public class DeliveryClaimService {

    private final JdbcTemplate jdbcTemplate;
    private final RetryPolicy retryPolicy;

    private static final int BATCH_SIZE = 10;

    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);

    public DeliveryClaimService(
            JdbcTemplate jdbcTemplate,
            RetryPolicy retryPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.retryPolicy = retryPolicy;
    }

    public record ClaimedDelivery(
            UUID id,
            UUID endpointId,
            String url,
            String body,
            String secret,
            Integer rateLimitPerSec,
            int attemptNo,
            Instant lockedUntil
    ) {}

    @Transactional
    public int cleanupExpiredDeliveries() {

        String sql = """
        UPDATE deliveries
        SET status = 'DEAD',
            locked_until = NULL,
            updated_at = now(),
            last_error = COALESCE(
                last_error,
                'Maximum attempts reached; lease expired'
            )
        WHERE status = 'IN_PROGRESS'
          AND locked_until < now()
          AND (attempt_count - attempt_base) >= ?
        """;

        return jdbcTemplate.update(
                sql,
                retryPolicy.maxAttempts()
        );
    }

    @Transactional
    public List<ClaimedDelivery> claimDeliveries() {

        String sql = """
            WITH candidates AS (
                SELECT id
                FROM deliveries
                WHERE (
                    (status = 'PENDING'
                     AND next_attempt_at <= now())
                    OR
                    (status = 'IN_PROGRESS'
                     AND locked_until < now())
                    )
                    AND (attempt_count - attempt_base) < ?
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
            ),
            claimed AS (
                UPDATE deliveries d
                SET status = 'IN_PROGRESS',
                    locked_until = ?,
                    attempt_count = d.attempt_count + 1,
                    updated_at = now()
                FROM candidates c
                WHERE d.id = c.id
                RETURNING d.id, d.endpoint_id,
                          d.event_id, d.attempt_count, d.locked_until
            )
            SELECT c.id,
                   c.endpoint_id,
                   ep.url,
                   ep.secret,
                   ep.rate_limit_per_sec,
                   ev.payload::text AS body,
                   c.attempt_count,
                   c.locked_until
            FROM claimed c
            JOIN endpoints ep ON ep.id = c.endpoint_id
            JOIN events ev ON ev.id = c.event_id
            """;

        Instant leaseUntil =
                Instant.now().plus(LEASE_DURATION);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getString("url"),
                        rs.getString("body"),
                        rs.getString("secret"),
                        rs.getObject("rate_limit_per_sec", Integer.class),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("locked_until").toInstant()
                ),
                retryPolicy.maxAttempts(),
                BATCH_SIZE,
                java.sql.Timestamp.from(leaseUntil)
        );
    }
}
