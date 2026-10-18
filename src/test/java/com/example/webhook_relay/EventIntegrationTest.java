package com.example.webhook_relay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.repository.EndpointRepository;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.MvcResult;

import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.EventRepository;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.repository.DeliveryRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;

@AutoConfigureMockMvc
class EventIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateDeliveriesForActiveSubscribers() throws Exception {
        // TODO: Arrange - create subscriber endpoints
        // Arrange
        Endpoint endpointA = endpointRepository.save(
                new Endpoint(
                        "https://example.com/webhook-a",
                        "secret-a",
                        new String[]{"order.created"},
                        10
                )
        );

        Endpoint endpointB = endpointRepository.save(
                new Endpoint(
                        "https://example.com/webhook-b",
                        "secret-b",
                        new String[]{"order.created"},
                        10
                )
        );

        Endpoint endpointC = endpointRepository.save(
                new Endpoint(
                        "https://example.com/webhook-c",
                        "secret-c",
                        new String[]{"user.deleted"},
                        10
                )
        );

        Endpoint endpointD = endpointRepository.save(
                new Endpoint(
                        "https://example.com/webhook-d",
                        "secret-d",
                        new String[]{"order.created"},
                        10
                )
        );

        endpointD.deactivate();
        endpointRepository.saveAndFlush(endpointD);

        // TODO: Act - POST /events
        // Act
        MvcResult result = mockMvc.perform(
                        post("/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {
                      "type": "order.created",
                      "payload": {
                        "orderId": 12345,
                        "amount": 49.99
                      }
                    }
                    """)
                )
                .andExpect(status().isAccepted())
                .andReturn();

        // TODO: Assert - verify HTTP response and database records
        // Assert - Event
        String responseBody = result.getResponse().getContentAsString();

        String eventIdString = objectMapper
                .readTree(responseBody)
                .get("eventId")
                .asText();

        UUID eventId = UUID.fromString(eventIdString);

        Event savedEvent = eventRepository.findById(eventId)
                .orElseThrow();

        assertEquals("order.created", savedEvent.getType());
        assertNotNull(savedEvent.getId());

        // Assert - Deliveries
        List<Delivery> deliveries = deliveryRepository.findByEventId(eventId);

        // Exactly two deliveries should be created
        assertEquals(2, deliveries.size());

        // Both deliveries should be pending
        assertTrue(deliveries.stream()
                .allMatch(delivery -> "PENDING".equals(delivery.getStatus())));

        // Each delivery should belong to a different subscribed endpoint
        Set<UUID> actualEndpointIds = deliveries.stream()
                .map(delivery -> delivery.getEndpoint().getId())
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(endpointA.getId(), endpointB.getId()),
                actualEndpointIds
        );

        // PostgreSQL should initialize next_attempt_at
        assertTrue(deliveries.stream()
                .allMatch(delivery -> delivery.getNextAttemptAt() != null));
    }

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
    }
}