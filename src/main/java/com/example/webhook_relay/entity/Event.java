package com.example.webhook_relay.entity;

import jakarta.persistence.*;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    // Primary Key
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Event Data
    @Column(name = "event_type", nullable = false, columnDefinition = "text")
    private String type;

    @Column(name = "idempotency_key", columnDefinition = "text")
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    // Timestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            insertable = false
    )
    private Instant createdAt;

    // Constructor
    protected Event() {
    }

    public Event(String type, JsonNode payload) {
        this.type = type;
        this.payload = payload.deepCopy();
    }

    // Getter
    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public JsonNode getPayload() {
        return payload.deepCopy();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}