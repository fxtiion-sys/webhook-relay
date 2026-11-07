package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.CreateEventRequest;
import com.example.webhook_relay.dto.CreateEventResponse;
import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import java.util.UUID;

import com.example.webhook_relay.exception.IdempotencyConflictException;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;
    private final JdbcTemplate jdbcTemplate;

    public EventService(
            EventRepository eventRepository,
            EndpointRepository endpointRepository,
            DeliveryRepository deliveryRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CreateEventResponse createEvent(CreateEventRequest request) {
        return createEvent(request, null);
    }

    @Transactional
    public CreateEventResponse createEvent(
            CreateEventRequest request,
            String idempotencyKey
    ) {

        if (idempotencyKey == null) {
            return createNewEvent(request);
        }

        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not be blank"
            );
        }

        UUID eventId = UUID.randomUUID();

        List<UUID> inserted = jdbcTemplate.queryForList("""
                INSERT INTO events (
                    id, event_type, payload, idempotency_key
                )
                VALUES (?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """,
                UUID.class,
                eventId,
                request.type(),
                request.payload().toString(),
                idempotencyKey
        );

        if (inserted.isEmpty()) {
            Event existing = eventRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Existing idempotency key was not found"
                    ));

            if (!existing.getType().equals(request.type())
                    || !existing.getPayload().equals(request.payload())) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key is already used for a different event"
                );
            }

            return new CreateEventResponse(existing.getId());
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow();

        createDeliveries(event, request.type());

        return new CreateEventResponse(eventId);
    }

    private CreateEventResponse createNewEvent(
            CreateEventRequest request
    ) {
        Event event = new Event(request.type(), request.payload());
        eventRepository.save(event);

        createDeliveries(event, request.type());

        return new CreateEventResponse(event.getId());
    }

    private void createDeliveries(Event event, String type) {
        List<Endpoint> subscribers =
                endpointRepository.findActiveSubscribers(type);

        List<Delivery> deliveries = subscribers.stream()
                .map(endpoint -> new Delivery(event, endpoint))
                .toList();

        deliveryRepository.saveAll(deliveries);
    }
}