package com.example.webhook_relay.ratelimit;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class TokenBucketRateLimiter {

    private final ConcurrentMap<UUID, TokenBucket> buckets =
            new ConcurrentHashMap<>();

    private final LongSupplier nanoTimeSource;

    @Autowired
    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    // Package-private constructor for deterministic tests
    TokenBucketRateLimiter(LongSupplier nanoTimeSource) {
        this.nanoTimeSource = nanoTimeSource;
    }

    public boolean tryAcquire(UUID endpointId, Integer rateLimitPerSec) {
        if (endpointId == null) {
            throw new IllegalArgumentException("endpointId must not be null");
        }

        if (rateLimitPerSec == null) {
            return true;
        }

        if (rateLimitPerSec <= 0) {
            throw new IllegalArgumentException(
                    "rateLimitPerSec must be positive"
            );
        }

        TokenBucket bucket = buckets.compute(
                endpointId,
                (id, existing) -> {
                    if (existing == null ||
                            existing.capacity != rateLimitPerSec) {
                        return new TokenBucket(
                                rateLimitPerSec,
                                nanoTimeSource.getAsLong()
                        );
                    }
                    return existing;
                }
        );

        return bucket.tryAcquire(nanoTimeSource.getAsLong());
    }

    private static class TokenBucket {

        private final int capacity;
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int capacity, long nowNanos) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        private synchronized boolean tryAcquire(long nowNanos) {
            refill(nowNanos);

            if (tokens < 1.0) {
                return false;
            }

            tokens -= 1.0;
            return true;
        }

        private void refill(long nowNanos) {
            long elapsedNanos = nowNanos - lastRefillNanos;

            if (elapsedNanos <= 0) {
                return;
            }

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

            tokens = Math.min(
                    capacity,
                    tokens + elapsedSeconds * capacity
            );

            lastRefillNanos = nowNanos;
        }
    }
}