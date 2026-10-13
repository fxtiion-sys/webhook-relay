package com.example.webhook_relay.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

import java.time.Instant;


@Entity
@Table(name = "endpoints")
public class Endpoint {
    // 1. Fields
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "secret", nullable = false, columnDefinition = "text")
    private String secret;

    @Column(
            name = "event_types",
            nullable = false,
            columnDefinition = "text[]"
    )
    private String[] eventTypes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "rate_limit_per_sec")
    private Integer rateLimitPerSec;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            insertable = false
    )
    private Instant createdAt;


    // 2. Constructors
    protected Endpoint() {
    }

    public Endpoint(String url, String secret, String[] eventTypes,
                    Integer rateLimitPerSec) {
        this.url = url;
        this.secret = secret;
        this.eventTypes = eventTypes.clone();
        this.active = true;
        this.rateLimitPerSec = rateLimitPerSec;
    }

    // 3. Getters
    public UUID getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public String[] getEventTypes() {
        return eventTypes.clone();
    }

    public boolean isActive() {
        return active;
    }

    public Integer getRateLimitPerSec() {
        return rateLimitPerSec;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }


}