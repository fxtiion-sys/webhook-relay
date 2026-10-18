package com.example.webhook_relay.repository;

import com.example.webhook_relay.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

import com.example.webhook_relay.entity.Delivery;
import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    List<Delivery> findByEventId(UUID eventId);
}