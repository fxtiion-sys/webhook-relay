
package com.example.webhook_relay;

import com.example.webhook_relay.entity.Delivery;
import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.entity.Event;
import com.example.webhook_relay.repository.DeliveryRepository;
import com.example.webhook_relay.repository.EndpointRepository;
import com.example.webhook_relay.repository.EventRepository;
import com.example.webhook_relay.security.WebhookSigner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "server.port=0",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/placeholder",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "app.worker.enabled=false"
})
@Transactional
class LocalMockReceiverHttpIntegrationTest
        extends PostgresIntegrationTest {

    private static final String SECRET = "http-integration-secret";
    private static final String BODY = "{\"event\":\"order.created\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final WebhookSigner signer = new WebhookSigner();

    private Delivery createDelivery() throws Exception {
        Endpoint endpoint = endpointRepository.saveAndFlush(
                new Endpoint(
                        "https://example.com/webhook",
                        SECRET,
                        new String[]{"order.created"},
                        10
                )
        );

        Event event = eventRepository.saveAndFlush(
                new Event(
                        "order.created",
                        objectMapper.readTree("{\"orderId\":123}")
                )
        );

        return deliveryRepository.saveAndFlush(
                new Delivery(event, endpoint)
        );
    }

    private String signatureHeader(long timestamp, String body) {
        return "t=" + timestamp + ",v1="
                + signer.sign(SECRET, timestamp, body);
    }

    @Test
    void validSignatureReturns200() throws Exception {
        Delivery delivery = createDelivery();
        long timestamp = Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", delivery.getId().toString())
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                signatureHeader(timestamp, BODY))
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        Delivery delivery = createDelivery();
        long timestamp = Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", delivery.getId().toString())
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                "t=" + timestamp + ",v1=invalid")
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownDeliveryReturns401() throws Exception {
        long timestamp = Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", UUID.randomUUID().toString())
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                signatureHeader(timestamp, BODY))
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
