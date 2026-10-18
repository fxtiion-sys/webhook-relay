package com.example.webhook_relay.service;

import com.example.webhook_relay.dto.CreateEndpointRequest;
import com.example.webhook_relay.dto.CreateEndpointResponse;
import com.example.webhook_relay.entity.Endpoint;
import com.example.webhook_relay.repository.EndpointRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import com.example.webhook_relay.dto.EndpointResponse;
import java.util.UUID;
import java.util.NoSuchElementException;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public EndpointService(EndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    @Transactional
    public CreateEndpointResponse createEndpoint(
            CreateEndpointRequest request
    ) {

        // 1. Generate a secure random secret
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        // 2. Convert List<String> to String[]
        String[] eventTypes = request.eventTypes()
                .toArray(new String[0]);

        // 3. Create Endpoint entity
        Endpoint endpoint = new Endpoint(
                request.url(),
                secret,
                eventTypes,
                null
        );

        // 4. Save to PostgreSQL
        Endpoint savedEndpoint = endpointRepository.save(endpoint);

        // 5. Convert entity to response DTO
        return new CreateEndpointResponse(
                savedEndpoint.getId(),
                savedEndpoint.getUrl(),
                List.of(savedEndpoint.getEventTypes()),
                secret
        );

    }
    @Transactional(readOnly = true)
    public EndpointResponse getEndpoint(UUID id) {

        // 1. Find endpoint by ID
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() ->
                        new NoSuchElementException("Endpoint not found: " + id)
                );

        // 2. Convert entity to response DTO
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getUrl(),
                List.of(endpoint.getEventTypes())
        );
    }
}