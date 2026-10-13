package com.example.webhook_relay.entity;

import jakarta.persistence.*;
import java.util.UUID;

import java.time.Instant;

import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(
            name = "next_attempt_at",
            nullable = false,
            insertable = false
    )
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Constructor
    protected Delivery() {
    }

    public Delivery(Event event, Endpoint endpoint) {
        this.event = event;
        this.endpoint = endpoint;
        this.status = "PENDING";
        this.attemptCount = 0;
    }

    // Getter
    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
