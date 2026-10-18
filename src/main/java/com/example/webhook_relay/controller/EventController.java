package com.example.webhook_relay.controller;

import com.example.webhook_relay.dto.CreateEventRequest;
import com.example.webhook_relay.dto.CreateEventResponse;
import com.example.webhook_relay.service.EventService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<CreateEventResponse> createEvent(
            @Valid @RequestBody CreateEventRequest request
    ) {
        CreateEventResponse response = eventService.createEvent(request);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
    }
}