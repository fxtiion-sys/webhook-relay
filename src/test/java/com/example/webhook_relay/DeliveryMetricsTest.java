package com.example.webhook_relay;

import com.example.webhook_relay.metrics.DeliveryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeliveryMetrics metrics = new DeliveryMetrics(registry);

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void recordsResultsSeparately() {
        metrics.recordCommittedResult(DeliveryMetrics.Result.SUCCESS);
        metrics.recordCommittedResult(DeliveryMetrics.Result.RETRY);
        metrics.recordCommittedResult(DeliveryMetrics.Result.RETRY);
        metrics.recordCommittedResult(DeliveryMetrics.Result.DEAD);

        assertEquals(1.0, resultCount("success"));
        assertEquals(2.0, resultCount("retry"));
        assertEquals(1.0, resultCount("dead"));
    }

    @Test
    void recordsHttpDurationWithCorrectUnits() {
        metrics.recordHttpDuration(125);
        metrics.recordHttpDuration(375);

        var timer = registry.get("webhook.delivery.duration").timer();

        assertEquals(2, timer.count());
        assertEquals(500.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void recordsE2eLatencyWithCorrectUnits() {
        metrics.recordE2eLatency(Duration.ofSeconds(12));

        var timer = registry.get("webhook.delivery.e2e.latency").timer();

        assertEquals(1, timer.count());
        assertEquals(12.0, timer.totalTime(TimeUnit.SECONDS), 0.001);
    }

    @Test
    void pendingIsUnknownUntilFirstRefreshAndCanDecrease() {
        var gauge = registry.get("webhook.deliveries.pending").gauge();

        assertTrue(Double.isNaN(gauge.value()));

        metrics.updatePending(7);
        assertEquals(7.0, gauge.value());

        metrics.updatePending(0);
        assertEquals(0.0, gauge.value());
    }

    @Test
    void ignoresNegativeDurations() {
        metrics.recordHttpDuration(-1);
        metrics.recordE2eLatency(Duration.ofMillis(-1));

        assertEquals(
                0,
                registry.get("webhook.delivery.duration").timer().count()
        );
        assertEquals(
                0,
                registry.get("webhook.delivery.e2e.latency").timer().count()
        );
    }

    @Test
    void rejectsNegativePendingCountWithoutChangingPreviousValue() {
        metrics.updatePending(3);

        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.updatePending(-1)
        );

        assertEquals(
                3.0,
                registry.get("webhook.deliveries.pending").gauge().value()
        );
    }

    private double resultCount(String result) {
        return registry.get("webhook.delivery.attempts")
                .tag("result", result)
                .counter()
                .count();
    }
}
