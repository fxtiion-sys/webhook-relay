package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.DeliveryResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

import org.springframework.web.client.RestClientResponseException;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Service
public class DeliverySender {

    private final RestClient restClient;

    public DeliverySender() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(Duration.ofSeconds(2));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public DeliveryResult send(
            String url,
            String body,
            Map<String, String> headers
    ) {
        long start = System.nanoTime();

        try {
            var response = restClient.post()
                    .uri(url)
                    .headers(httpHeaders -> {
                        headers.forEach(httpHeaders::set);
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            long durationMs =
                    (System.nanoTime() - start) / 1_000_000;

            return new DeliveryResult(
                    response.getStatusCode().value(),
                    null,
                    durationMs
            );

        } catch (RestClientResponseException e) {
            long durationMs =
                    (System.nanoTime() - start) / 1_000_000;

            return new DeliveryResult(
                    e.getStatusCode().value(),
                    e.getMessage(),
                    durationMs
            );
        } catch (Exception e) {
            long durationMs =
                    (System.nanoTime() - start) / 1_000_000;

            return new DeliveryResult(
                    null,
                    e.getMessage(),
                    durationMs
            );
        }
    }
}