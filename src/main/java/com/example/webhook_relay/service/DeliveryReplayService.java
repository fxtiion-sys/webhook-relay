package com.example.webhook_relay.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

import com.example.webhook_relay.exception.DeliveryReplayConflictException;

@Service
public class DeliveryReplayService {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryReplayService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void replay(UUID deliveryId) {

        String sql = """
            UPDATE deliveries
            SET status = 'PENDING',
                attempt_base = attempt_count,
                next_attempt_at = now(),
                locked_until = NULL,
                last_status_code = NULL,
                last_error = NULL,
                updated_at = now()
            WHERE id = ?
              AND status = 'DEAD'
            """;

        int updated = jdbcTemplate.update(sql, deliveryId);

        if (updated == 1) {
            return;
        }

        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM deliveries
                    WHERE id = ?
                )
                """,
                Boolean.class,
                deliveryId
        );

        if (!Boolean.TRUE.equals(exists)) {
            throw new NoSuchElementException(
                    "Delivery not found: " + deliveryId
            );
        }

        throw new DeliveryReplayConflictException(
                "Only DEAD deliveries can be replayed: " + deliveryId
        );
    }
}