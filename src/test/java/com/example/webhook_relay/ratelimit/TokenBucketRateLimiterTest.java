package com.example.webhook_relay.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private AtomicLong clock;
    private TokenBucketRateLimiter rateLimiter;
    private UUID endpointId;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0);
        rateLimiter = new TokenBucketRateLimiter(clock::get);
        endpointId = UUID.randomUUID();
    }

    @Test
    void allowsRequestsWithinCapacity() {
        assertTrue(rateLimiter.tryAcquire(endpointId, 2));
        assertTrue(rateLimiter.tryAcquire(endpointId, 2));
    }

    @Test
    void rejectsRequestsWhenTokensAreExhausted() {
        assertTrue(rateLimiter.tryAcquire(endpointId, 2));
        assertTrue(rateLimiter.tryAcquire(endpointId, 2));

        assertFalse(rateLimiter.tryAcquire(endpointId, 2));
    }

    @Test
    void refillsTokensOverTime() {
        rateLimiter.tryAcquire(endpointId, 2);
        rateLimiter.tryAcquire(endpointId, 2);

        assertFalse(rateLimiter.tryAcquire(endpointId, 2));

        // Advance fake time by 500 milliseconds.
        clock.addAndGet(500_000_000L);

        assertTrue(rateLimiter.tryAcquire(endpointId, 2));
        assertFalse(rateLimiter.tryAcquire(endpointId, 2));
    }

    @Test
    void maintainsIndependentBucketsPerEndpoint() {
        UUID anotherEndpoint = UUID.randomUUID();

        rateLimiter.tryAcquire(endpointId, 1);

        assertFalse(rateLimiter.tryAcquire(endpointId, 1));
        assertTrue(rateLimiter.tryAcquire(anotherEndpoint, 1));
    }

    @Test
    void allowsUnlimitedRequestsWhenRateLimitIsNull() {
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.tryAcquire(endpointId, null));
        }
    }

    @Test
    void rejectsInvalidRateLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire(endpointId, 0)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire(endpointId, -1)
        );
    }

    @Test
    void rejectsNullEndpointId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire(null, 2)
        );
    }
}