package com.example.webhook_relay.controller;

import com.example.webhook_relay.dto.CreateEndpointRequest;
import com.example.webhook_relay.dto.CreateEndpointResponse;
import com.example.webhook_relay.dto.EndpointResponse;
import com.example.webhook_relay.service.EndpointService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/endpoints")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateEndpointResponse createEndpoint(
            @Valid @RequestBody CreateEndpointRequest request
    ) {
        return endpointService.createEndpoint(request);
    }

    @GetMapping("/{id}")
    public EndpointResponse getEndpoint(@PathVariable UUID id) {
        return endpointService.getEndpoint(id);
    }
}