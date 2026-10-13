package com.example.webhook_relay.entity;

import jakarta.persistence.*;
import java.util.UUID;

import java.time.Instant;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    // Constructor
    protected DeliveryAttempt() {
    }

    public DeliveryAttempt(
            Delivery delivery,
            int attemptNo,
            Integer statusCode,
            String error,
            long durationMs,
            Instant attemptedAt
    ) {
        this.delivery = delivery;
        this.attemptNo = attemptNo;
        this.statusCode = statusCode;
        this.error = error;
        this.durationMs = durationMs;
        this.attemptedAt = attemptedAt;
    }

    // Getter
    public UUID getId() {
        return id;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

}