
package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class DeliveryResultService {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryResultService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean saveResult(
            UUID deliveryId,
            int attemptNo,
            Instant lockedUntil,
            DeliveryResult result
    ) {
        Integer statusCode = result.statusCode();

        boolean success = statusCode != null
                && statusCode >= 200
                && statusCode < 300;

        String newStatus = success ? "SUCCEEDED" : "PENDING";

        String sql = """
            UPDATE deliveries
            SET status = ?,
                locked_until = NULL,
                last_status_code = ?,
                last_error = ?,
                next_attempt_at = CASE
                    WHEN ? = 'PENDING'
                    THEN now() + interval '5 seconds'
                    ELSE next_attempt_at
                END,
                updated_at = now()
            WHERE id = ?
              AND status = 'IN_PROGRESS'
              AND attempt_count = ?
              AND locked_until = ?
            """;

        int updated = jdbcTemplate.update(
                sql,
                newStatus,
                statusCode,
                result.error(),
                newStatus,
                deliveryId,
                attemptNo,
                Timestamp.from(lockedUntil)
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
