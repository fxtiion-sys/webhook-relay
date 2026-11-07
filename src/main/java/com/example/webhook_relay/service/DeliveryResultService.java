
package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.example.webhook_relay.retry.DeliveryOutcome;
import com.example.webhook_relay.retry.FailureClassifier;
import com.example.webhook_relay.retry.RetryPolicy;

import java.time.Duration;

@Service
public class DeliveryResultService {

    private final JdbcTemplate jdbcTemplate;
    private final RetryPolicy retryPolicy;
    private final FailureClassifier failureClassifier;

    public DeliveryResultService(
            JdbcTemplate jdbcTemplate,
            RetryPolicy retryPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.retryPolicy = retryPolicy;
        this.failureClassifier = new FailureClassifier();
    }

    @Transactional
    public boolean saveResult(
            UUID deliveryId,
            int attemptNo,
            Instant lockedUntil,
            DeliveryResult result
    ) {
        Integer statusCode = result.statusCode();

        Integer attemptBase = jdbcTemplate.queryForObject(
                """
                SELECT attempt_base
                FROM deliveries
                WHERE id = ?
                """,
                Integer.class,
                deliveryId
        );

        if (attemptBase == null) {
            throw new IllegalStateException("Delivery not found: " + deliveryId);
        }

        int cycleAttemptNo = attemptNo - attemptBase;

        DeliveryOutcome outcome = failureClassifier.classify(result);

        String newStatus;
        Duration retryDelay = null;

        if (outcome == DeliveryOutcome.SUCCESS) {
            newStatus = "SUCCEEDED";
        } else if (outcome == DeliveryOutcome.PERMANENT_FAILURE
                || cycleAttemptNo >= retryPolicy.maxAttempts()) {
            newStatus = "DEAD";
        } else {
            newStatus = "PENDING";
            retryDelay = retryPolicy.nextDelay(attemptNo);
        }

        String sql = """
            UPDATE deliveries
            SET status = ?,
                locked_until = NULL,
                last_status_code = ?,
                last_error = ?,
                next_attempt_at = CASE
                    WHEN ? = 'PENDING'
                    THEN now() + (? * interval '1 millisecond')
                    ELSE next_attempt_at
                END,
                updated_at = now()
            WHERE id = ?
              AND status = 'IN_PROGRESS'
              AND attempt_count = ?
              AND locked_until = ?
              AND attempt_base = ?
            """;

        Long retryDelayMillis = retryDelay == null
                ? null
                : retryDelay.toMillis();

        int updated = jdbcTemplate.update(
                sql,
                newStatus,
                statusCode,
                result.error(),
                newStatus,
                retryDelayMillis,
                deliveryId,
                attemptNo,
                Timestamp.from(lockedUntil),
                attemptBase
        );

        if (updated == 0) {
            return false;
        }

        String attemptSql = """
            INSERT INTO delivery_attempts (
                id,
                delivery_id,
                attempt_no,
                status_code,
                error,
                duration_ms,
                attempted_at
            )
            VALUES (?, ?, ?, ?, ?, ?, now())
            """;

        jdbcTemplate.update(
                attemptSql,
                UUID.randomUUID(),
                deliveryId,
                attemptNo,
                statusCode,
                result.error(),
                result.durationMs()
        );

        return true;
    }
}
