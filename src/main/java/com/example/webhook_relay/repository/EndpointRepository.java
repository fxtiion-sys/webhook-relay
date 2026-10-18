package com.example.webhook_relay.repository;

import com.example.webhook_relay.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    @Query(
            value = """
            SELECT *
            FROM endpoints
            WHERE active = true
              AND :eventType = ANY(event_types)
            """,
            nativeQuery = true
    )
    List<Endpoint> findActiveSubscribers(
            @Param("eventType") String eventType
    );
}