package com.example.webhook_relay.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingDeliveryMetricsUpdater {

    private static final Logger log =
            LoggerFactory.getLogger(PendingDeliveryMetricsUpdater.class);

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryMetrics deliveryMetrics;

    public PendingDeliveryMetricsUpdater(
            JdbcTemplate jdbcTemplate,
            DeliveryMetrics deliveryMetrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliveryMetrics = deliveryMetrics;
    }

    @Scheduled(
            fixedDelayString = "${app.metrics.pending-refresh-ms:5000}"
    )
    public void refreshPendingCount() {
        try {
            Long count = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM deliveries
                    WHERE status = 'PENDING'
                    """,
                    Long.class
            );

            if (count == null) {
                log.warn("Pending delivery count query returned null");
                return;
            }

            deliveryMetrics.updatePending(count);
        } catch (Exception e) {
            log.warn(
                    "Failed to refresh pending delivery count; retaining previous value",
                    e
            );
        }
    }
}
