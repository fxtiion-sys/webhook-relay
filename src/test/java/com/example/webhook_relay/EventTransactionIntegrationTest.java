package com.example.webhook_relay;

import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;
import com.example.webhook_relay.service.EventService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.webhook_relay.dto.CreateEventRequest;
import com.example.webhook_relay.entity.Endpoint;

import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import org.junit.jupiter.api.AfterEach;

class EventTransactionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @MockitoSpyBean
    private DeliveryRepository deliveryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
    }


    @Test
    void shouldRollbackEventWhenDeliveryCreationFails() {

        // Arrange: create a subscriber
        Endpoint endpoint = endpointRepository.save(
                new Endpoint(
                        "https://example.com/rollback-test",
                        "test-secret",
                        new String[]{"rollback.test"},
                        10
                )
        );

        long eventsBefore = eventRepository.count();

        // Simulate a failure while saving deliveries
        doThrow(new RuntimeException("Simulated delivery failure"))
                .when(deliveryRepository)
                .saveAll(any());

        CreateEventRequest request = new CreateEventRequest(
                "rollback.test",
                objectMapper.readTree("{\"message\":\"test\"}")
        );

        // Act: event creation should fail
        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> eventService.createEvent(request)
        );

        // Assert: event insertion must be rolled back
        assertEquals(
                "Simulated delivery failure",
                exception.getMessage()
        );

        assertEquals(eventsBefore, eventRepository.count());
    }
}