package com.example.webhook_relay.repository;

import com.example.webhook_relay.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {


}