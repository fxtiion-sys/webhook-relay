package com.example.webhook_relay.retry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
public class RetryConfiguration {

    @Bean
    public RetryPolicy retryPolicy(
            @Value("${app.retry.base-delay}") Duration baseDelay,
            @Value("${app.retry.max-delay}") Duration maxDelay,
            @Value("${app.retry.max-attempts}") int maxAttempts
    ) {
        return new RetryPolicy(
                baseDelay,
                maxDelay,
                maxAttempts,
                ThreadLocalRandom.current()
        );
    }
}