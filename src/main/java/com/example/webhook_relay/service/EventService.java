package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.CreateEventRequest;
import com.example.webhook_relay.dto.CreateEventResponse;
import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;

    public EventService(
            EventRepository eventRepository,
            EndpointRepository endpointRepository,
            DeliveryRepository deliveryRepository
    ) {
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional
    public CreateEventResponse createEvent(CreateEventRequest request) {

        // 1. Save the event
        Event event = new Event(request.type(), request.payload());
        eventRepository.save(event);

        // 2. Find active subscribers
        List<Endpoint> subscribers =
                endpointRepository.findActiveSubscribers(request.type());

        // 3. Create deliveries for each subscriber
        List<Delivery> deliveries = subscribers.stream()
                .map(endpoint -> new Delivery(event, endpoint))
                .toList();

        // 4. Save all deliveries
        deliveryRepository.saveAll(deliveries);

        // 5. Return the event ID
        return new CreateEventResponse(event.getId());
    }
}