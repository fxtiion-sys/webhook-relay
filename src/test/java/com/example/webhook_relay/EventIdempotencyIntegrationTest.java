
package com.example.webhook_relay;

import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@AutoConfigureMockMvc
class EventIdempotencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
    }

    @Test
    void sameKeyShouldReturnSameEventWithoutDuplicateDeliveries()
            throws Exception {

        // Arrange
        Endpoint endpoint = endpointRepository.save(
                new Endpoint(
                        "https://example.com/idempotency-test",
                        "test-secret",
                        new String[]{"order.created"},
                        10
                )
        );

        String requestBody = """
                {
                  "type": "order.created",
                  "payload": {
                    "orderId": 12345
                  }
                }
                """;

        String key = "order-12345-" + UUID.randomUUID();

        // Act: first request
        String firstResponse = mockMvc.perform(
                        post("/events")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Act: duplicate request
        String secondResponse = mockMvc.perform(
                        post("/events")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID firstId = UUID.fromString(
                objectMapper.readTree(firstResponse)
                        .get("eventId").asText()
        );

        UUID secondId = UUID.fromString(
                objectMapper.readTree(secondResponse)
                        .get("eventId").asText()
        );

        // Assert
        assertEquals(firstId, secondId);

        assertEquals(1, eventRepository.count());

        assertEquals(
                1,
                deliveryRepository.findByEventId(firstId).size()
        );
    }

    @Test
    void sameKeyWithDifferentPayloadShouldReturnConflict() throws Exception {

        // Arrange
        String key = "conflict-test-" + UUID.randomUUID();

        String firstRequest = """
            {
              "type": "order.created",
              "payload": {
                "orderId": 100
              }
            }
            """;

        String conflictingRequest = """
            {
              "type": "order.created",
              "payload": {
                "orderId": 200
              }
            }
            """;

        // Act: create the original event
        mockMvc.perform(
                        post("/events")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(firstRequest)
                )
                .andExpect(status().isAccepted());

        // Act & Assert: same key, different payload
        mockMvc.perform(
                        post("/events")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(conflictingRequest)
                )
                .andExpect(status().isConflict());

        // Assert: no additional event was created
        assertEquals(1, eventRepository.count());
    }


    @Test
    void requestsWithoutIdempotencyKeyShouldCreateSeparateEvents()
            throws Exception {

        // Arrange
        String requestBody = """
            {
              "type": "order.created",
              "payload": {
                "orderId": 12345
              }
            }
            """;

        // Act: first request without Idempotency-Key
        String firstResponse = mockMvc.perform(
                        post("/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Act: second request without Idempotency-Key
        String secondResponse = mockMvc.perform(
                        post("/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID firstId = UUID.fromString(
                objectMapper.readTree(firstResponse)
                        .get("eventId").asText()
        );

        UUID secondId = UUID.fromString(
                objectMapper.readTree(secondResponse)
                        .get("eventId").asText()
        );

        // Assert: two independent events
        assertNotEquals(firstId, secondId);
        assertEquals(2, eventRepository.count());
    }


    @Test
    void concurrentRequestsWithSameKeyShouldCreateOnlyOneEvent()
            throws Exception {

        // Arrange: one active subscriber
        endpointRepository.save(
                new Endpoint(
                        "https://example.com/concurrent-test",
                        "test-secret",
                        new String[]{"order.created"},
                        10
                )
        );

        String key = "concurrent-test-" + UUID.randomUUID();

        String requestBody = """
            {
              "type": "order.created",
              "payload": {
                "orderId": 999
              }
            }
            """;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Two requests wait for the same start signal
            java.util.concurrent.Callable<UUID> task = () -> {
                ready.countDown();

                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start signal timed out");
                }

                String response = mockMvc.perform(
                                post("/events")
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody)
                        )
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

                return UUID.fromString(
                        objectMapper.readTree(response)
                                .get("eventId")
                                .asText()
                );
            };

            Future<UUID> first = executor.submit(task);
            Future<UUID> second = executor.submit(task);

            // Wait until both threads are ready
            assertTrue(ready.await(5, TimeUnit.SECONDS));

            // Release both requests simultaneously
            start.countDown();

            UUID firstId = first.get(15, TimeUnit.SECONDS);
            UUID secondId = second.get(15, TimeUnit.SECONDS);

            // Assert: both requests resolve to the same event
            assertEquals(firstId, secondId);

            // Only one event exists
            assertEquals(1, eventRepository.count());

            // Only one delivery exists for the subscriber
            assertEquals(
                    1,
                    deliveryRepository.findByEventId(firstId).size()
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


}
