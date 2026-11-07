package com.example.webhook_relay.repository;

import com.example.webhook_relay.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findByIdempotencyKey(String idempotencyKey);
}