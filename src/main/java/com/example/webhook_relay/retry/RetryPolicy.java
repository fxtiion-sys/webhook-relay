
package com.example.webhook_relay.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

public class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final RandomGenerator random;

    public RetryPolicy(
            Duration baseDelay,
            Duration maxDelay,
            int maxAttempts,
            RandomGenerator random
    ) {
        Objects.requireNonNull(baseDelay);
        Objects.requireNonNull(maxDelay);
        Objects.requireNonNull(random);

        if (baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    public Duration nextDelay(int attemptNo) {
        if (attemptNo < 1 || attemptNo >= maxAttempts) {
            throw new IllegalArgumentException(
                    "attemptNo must be between 1 and maxAttempts - 1"
            );
        }

        long capMillis = maxDelay.toMillis();
        long delayMillis = Math.min(baseDelay.toMillis(), capMillis);

        for (int i = 1; i < attemptNo; i++) {
            if (delayMillis >= capMillis) {
                break;
            }
            delayMillis = delayMillis > capMillis / 2
                    ? capMillis
                    : delayMillis * 2;
        }

        long jitterMillis = random.nextLong(delayMillis + 1);

        return Duration.ofMillis(jitterMillis);
    }
}
