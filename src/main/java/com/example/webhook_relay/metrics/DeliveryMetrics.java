package com.example.webhook_relay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeliveryMetrics {

    public enum Result {
        SUCCESS, RETRY, DEAD
    }

    private final Counter success;
    private final Counter retry;
    private final Counter dead;
    private final Timer httpDuration;
    private final Timer e2eLatency;
    private final AtomicLong pending = new AtomicLong(-1);

    public DeliveryMetrics(MeterRegistry registry) {
        success = attemptsCounter(registry, "success");
        retry = attemptsCounter(registry, "retry");
        dead = attemptsCounter(registry, "dead");

        httpDuration = Timer.builder("webhook.delivery.duration")
                .description("Duration of outgoing webhook HTTP attempts")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);

        e2eLatency = Timer.builder("webhook.delivery.e2e.latency")
                .description("Time from event creation to successful delivery")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofHours(1))
                .register(registry);

        Gauge.builder(
                        "webhook.deliveries.pending",
                        pending,
                        value -> {
                            long count = value.get();
                            return count < 0 ? Double.NaN : (double) count;
                        }
                )
                .description("Pending deliveries including scheduled retries")
                .register(registry);
    }

    private static Counter attemptsCounter(
            MeterRegistry registry,
            String result
    ) {
        return Counter.builder("webhook.delivery.attempts")
                .description("Delivery attempt outcomes committed to the database")
                .tag("result", result)
                .register(registry);
    }

    public void recordCommittedResult(Result result) {
        switch (result) {
            case SUCCESS -> success.increment();
            case RETRY -> retry.increment();
            case DEAD -> dead.increment();
        }
    }

    public void recordHttpDuration(long durationMs) {
        if (durationMs >= 0) {
            httpDuration.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordE2eLatency(Duration duration) {
        if (!duration.isNegative()) {
            e2eLatency.record(duration);
        }
    }

    public void updatePending(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Pending count must not be negative");
        }
        pending.set(count);
    }
}
