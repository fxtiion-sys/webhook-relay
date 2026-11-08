package com.example.webhook_relay;

import com.example.webhook_relay.security.WebhookSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers =
        com.example.webhook_relay.controller.MockReceiverController.class)
@TestPropertySource(properties = "app.mock-receiver.secret=test-secret")
class MockReceiverSignatureIntegrationTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = "{\"event\":\"created\"}";

    @Autowired
    private MockMvc mockMvc;

    private final WebhookSigner signer = new WebhookSigner();

    private String signatureHeader(long timestamp, String body) {
        return "t=" + timestamp + ",v1="
                + signer.sign(SECRET, timestamp, body);
    }

    @Test
    void validSignatureReturns200() throws Exception {
        long timestamp = java.time.Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", "test-delivery-id")
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                signatureHeader(timestamp, BODY))
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        long timestamp = java.time.Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", "test-delivery-id")
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                "t=" + timestamp + ",v1=invalid")
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTimestampReturns401() throws Exception {
        long timestamp =
                java.time.Instant.now().getEpochSecond() - 301;

        mockMvc.perform(post("/mock-receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", "test-delivery-id")
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                signatureHeader(timestamp, BODY))
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSignatureWithSimulatedFailureReturns500()
            throws Exception {
        long timestamp = java.time.Instant.now().getEpochSecond();

        mockMvc.perform(post("/mock-receiver?fail=500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Webhook-Id", "test-delivery-id")
                        .header("Webhook-Timestamp", timestamp)
                        .header("Webhook-Signature",
                                signatureHeader(timestamp, BODY))
                        .content(BODY))
                .andExpect(status().isInternalServerError());
    }
}