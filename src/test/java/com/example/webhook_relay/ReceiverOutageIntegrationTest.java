
package com.example.webhook_relay;

import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.metrics.DeliveryMetrics;
import com.example.webhook_relay.ratelimit.TokenBucketRateLimiter;
import com.example.webhook_relay.service.DeliveryClaimService;
import com.example.webhook_relay.service.DeliveryResultService;
import com.example.webhook_relay.service.DeliverySender;
import com.example.webhook_relay.service.DeliveryWorker;
import com.github.tomakehurst.wiremock.WireMockServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.client.WireMock;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.worker.enabled=false")
class ReceiverOutageIntegrationTest extends PostgresIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private EndpointRepository endpointRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private DeliveryClaimService claimService;

  @Autowired
  private DeliveryResultService resultService;

  @Autowired
  private DeliverySender deliverySender;

  @Autowired
  private DeliveryMetrics deliveryMetrics;

  private WireMockServer wireMockServer;
  private UUID endpointId;

  private final List<UUID> eventIds = new ArrayList<>();
  private final List<UUID> deliveryIds = new ArrayList<>();

  private static final int EVENT_COUNT = 10;

  @Test
  void shouldRecoverAllDeliveriesAfterReceiverOutage()
          throws Exception {

    // Arrange: Start the receiver on a dynamic port.
    wireMockServer = new WireMockServer(0);
    wireMockServer.start();

    int receiverPort = wireMockServer.port();
    String receiverUrl = wireMockServer.baseUrl() + "/webhook";

    String eventType = "outage.test." + UUID.randomUUID();

    Endpoint endpoint = endpointRepository.saveAndFlush(
            new Endpoint(
                    receiverUrl,
                    "test-secret",
                    new String[]{eventType},
                    EVENT_COUNT
            )
    );

    endpointId = endpoint.getId();

    DeliveryWorker worker = new DeliveryWorker(
            claimService,
            deliverySender,
            resultService,
            new TokenBucketRateLimiter(),
            deliveryMetrics,
            "outage-test-worker"
    );

    // Act: Simulate a complete receiver outage.
    wireMockServer.stop();

    // Publish events while the receiver is unavailable.
    for (int i = 0; i < EVENT_COUNT; i++) {

      MvcResult response = mockMvc.perform(
                      post("/events")
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("""
                                            {
                                              "type": "%s",
                                              "payload": {
                                                "orderId": %d
                                              }
                                            }
                                            """.formatted(eventType, i))
              )
              .andExpect(status().isAccepted())
              .andReturn();

      UUID eventId = UUID.fromString(
              objectMapper.readTree(
                      response.getResponse().getContentAsString()
              ).get("eventId").asText()
      );

      eventIds.add(eventId);

      UUID deliveryId = jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM deliveries
                    WHERE event_id = ?
                      AND endpoint_id = ?
                    """,
              UUID.class,
              eventId,
              endpointId
      );

      assertNotNull(deliveryId);
      deliveryIds.add(deliveryId);
    }

    assertEquals(EVENT_COUNT, eventIds.size());
    assertEquals(EVENT_COUNT, deliveryIds.size());
    assertEquals(EVENT_COUNT, eventIds.stream().distinct().count());
    assertEquals(EVENT_COUNT, deliveryIds.stream().distinct().count());

    // Verify that accepted events and deliveries are persisted.
    assertPersistedRecords();

    assertEquals(EVENT_COUNT, countDeliveriesByStatus("PENDING"));

    // Process the first delivery attempt during the outage.
    worker.processDeliveries();

    // Every delivery must remain available for retry.
    assertEquals(EVENT_COUNT, countDeliveriesByStatus("PENDING"));
    assertEquals(0, countDeliveriesByStatus("SUCCEEDED"));
    assertEquals(0, countDeliveriesByStatus("DEAD"));

    for (UUID deliveryId : deliveryIds) {

      Integer attemptCount = jdbcTemplate.queryForObject("""
                    SELECT attempt_count
                    FROM deliveries
                    WHERE id = ?
                    """,
              Integer.class,
              deliveryId
      );

      assertEquals(1, attemptCount);

      Integer failedAttempts = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM delivery_attempts
                    WHERE delivery_id = ?
                      AND attempt_no = 1
                      AND status_code IS NULL
                      AND error IS NOT NULL
                    """,
              Integer.class,
              deliveryId
      );

      assertEquals(1, failedAttempts);
    }

    assertPersistedRecords();

    // Restore the receiver at the original address.
    wireMockServer = new WireMockServer(receiverPort);
    wireMockServer.start();

    wireMockServer.stubFor(
            WireMock.post(urlEqualTo("/webhook"))
                    .willReturn(aResponse().withStatus(200))
    );

    // Wait until all deliveries have been retried successfully.
    await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(100))
            .until(() -> {
              worker.processDeliveries();

              return countDeliveriesByStatus("SUCCEEDED")
                      == EVENT_COUNT;
            });

    // Assert: No accepted events or deliveries were lost.
    assertPersistedRecords();

    assertEquals(EVENT_COUNT, countDeliveriesByStatus("SUCCEEDED"));
    assertEquals(0, countDeliveriesByStatus("PENDING"));
    assertEquals(0, countDeliveriesByStatus("IN_PROGRESS"));
    assertEquals(0, countDeliveriesByStatus("DEAD"));

    for (UUID deliveryId : deliveryIds) {

      Integer attemptCount = jdbcTemplate.queryForObject("""
                    SELECT attempt_count
                    FROM deliveries
                    WHERE id = ?
                    """,
              Integer.class,
              deliveryId
      );

      assertEquals(2, attemptCount);

      Integer savedAttempts = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM delivery_attempts
                    WHERE delivery_id = ?
                    """,
              Integer.class,
              deliveryId
      );

      assertEquals(2, savedAttempts);

      Integer successfulAttempts = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM delivery_attempts
                    WHERE delivery_id = ?
                      AND attempt_no = 2
                      AND status_code = 200
                    """,
              Integer.class,
              deliveryId
      );

      assertEquals(1, successfulAttempts);

      // Verify that this specific delivery reached the receiver.
      wireMockServer.verify(
              1,
              postRequestedFor(urlEqualTo("/webhook"))
                      .withHeader(
                              "Webhook-Id",
                              equalTo(deliveryId.toString())
                      )
      );
    }

    wireMockServer.verify(
            EVENT_COUNT,
            postRequestedFor(urlEqualTo("/webhook"))
    );
  }

  private int countDeliveriesByStatus(String status) {

    Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM deliveries
                WHERE endpoint_id = ?
                  AND status = ?
                """,
            Integer.class,
            endpointId,
            status
    );

    return count == null ? 0 : count;
  }

  private void assertPersistedRecords() {

    for (UUID eventId : eventIds) {

      Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM events
                    WHERE id = ?
                    """,
              Integer.class,
              eventId
      );

      assertEquals(1, count);
    }

    for (int i = 0; i < deliveryIds.size(); i++) {

      Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM deliveries
                    WHERE id = ?
                      AND event_id = ?
                      AND endpoint_id = ?
                    """,
              Integer.class,
              deliveryIds.get(i),
              eventIds.get(i),
              endpointId
      );

      assertEquals(1, count);
    }

    Integer totalDeliveries = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM deliveries
                WHERE endpoint_id = ?
                """,
            Integer.class,
            endpointId
    );

    assertEquals(EVENT_COUNT, totalDeliveries);
  }

  @AfterEach
  void cleanUp() {

    if (wireMockServer != null && wireMockServer.isRunning()) {
      wireMockServer.stop();
    }

    if (endpointId != null) {

      jdbcTemplate.update("""
                    DELETE FROM delivery_attempts
                    WHERE delivery_id IN (
                        SELECT id
                        FROM deliveries
                        WHERE endpoint_id = ?
                    )
                    """,
              endpointId
      );

      jdbcTemplate.update(
              "DELETE FROM deliveries WHERE endpoint_id = ?",
              endpointId
      );
    }

    for (UUID eventId : eventIds) {
      jdbcTemplate.update(
              "DELETE FROM events WHERE id = ?",
              eventId
      );
    }

    if (endpointId != null) {
      jdbcTemplate.update(
              "DELETE FROM endpoints WHERE id = ?",
              endpointId
      );
    }
  }
}
