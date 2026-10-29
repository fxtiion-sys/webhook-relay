package com.example.webhook_relay.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock-receiver")
public class MockReceiverController {

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestBody String body,
            @RequestParam(required = false) Integer fail,
            @RequestParam(defaultValue = "0") long delayMs
    ) throws InterruptedException {

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }

        if (fail != null && fail == 500) {
            return ResponseEntity.status(500)
                    .body("Simulated server error");
        }

        return ResponseEntity.ok("Received");
    }
}