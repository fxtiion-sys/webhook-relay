
package com.example.webhook_relay.repository;

import com.example.webhook_relay.entity.Delivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    List<Delivery> findByEventId(UUID eventId);

    @Query("""
        SELECT d
        FROM Delivery d
        WHERE (:status IS NULL OR d.status = :status)
          AND (:endpointId IS NULL OR d.endpoint.id = :endpointId)
        """)
    Page<Delivery> findDeliveries(
            @Param("status") String status,
            @Param("endpointId") UUID endpointId,
            Pageable pageable
    );
}
