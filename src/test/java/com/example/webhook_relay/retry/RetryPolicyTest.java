
package com.example.webhook_relay.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void firstAttemptDelayIsWithinBaseDelay() {
        RetryPolicy policy = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                5,
                new Random(42)
        );

        Duration delay = policy.nextDelay(1);

        assertFalse(delay.isNegative());
        assertTrue(delay.compareTo(Duration.ofSeconds(1)) <= 0);
    }

    @Test
    void delayUpperBoundGrowsExponentially() {
        RetryPolicy policy = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                10,
                new Random(42)
        );

        Duration delay = policy.nextDelay(4);

        assertFalse(delay.isNegative());
        assertTrue(delay.compareTo(Duration.ofSeconds(8)) <= 0);
    }

    @Test
    void delayDoesNotExceedMaximum() {
        RetryPolicy policy = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                20,
                new Random(42)
        );

        Duration delay = policy.nextDelay(15);

        assertFalse(delay.isNegative());
        assertTrue(delay.compareTo(Duration.ofSeconds(30)) <= 0);
    }

    @Test
    void sameSeedProducesSameDelay() {
        RetryPolicy first = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                5,
                new Random(42)
        );

        RetryPolicy second = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                5,
                new Random(42)
        );

        assertEquals(first.nextDelay(3), second.nextDelay(3));
    }

    @Test
    void rejectsInvalidAttemptNumber() {
        RetryPolicy policy = new RetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                5,
                new Random(42)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.nextDelay(0)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.nextDelay(5)
        );
    }
}
