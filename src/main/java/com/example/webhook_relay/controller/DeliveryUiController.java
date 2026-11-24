package com.example.webhook_relay.controller;

import com.example.webhook_relay.dto.DeliveryResponse;
import com.example.webhook_relay.service.DeliveryQueryService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Controller
@RequestMapping("/ui/deliveries")
public class DeliveryUiController {

    private static final List<String> STATUSES = List.of(
            "PENDING",
            "IN_PROGRESS",
            "SUCCEEDED",
            "DEAD"
    );

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryQueryService deliveryQueryService;

    public DeliveryUiController(
            DeliveryQueryService deliveryQueryService
    ) {
        this.deliveryQueryService = deliveryQueryService;
    }

    @GetMapping
    public String getDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model
    ) {
        String normalizedStatus = normalizeStatus(status);

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );

        Page<DeliveryResponse> deliveries =
                deliveryQueryService.getDeliveries(
                        normalizedStatus,
                        endpointId,
                        pageable
                );

        model.addAttribute("deliveries", deliveries);
        model.addAttribute(
                "selectedStatus",
                normalizedStatus == null ? "ALL" : normalizedStatus
        );
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("endpointId", endpointId);
        model.addAttribute("pageSize", size);

        return "deliveries/list";
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        String normalized = status.trim()
                .toUpperCase(Locale.ROOT);

        if ("ALL".equals(normalized)) {
            return null;
        }

        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid delivery status"
            );
        }

        return normalized;
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be greater than or equal to 0"
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page size must be between 1 and 100"
            );
        }
    }
}