
package com.example.webhook_relay;

import com.example.webhook_relay.controller.DeliveryUiController;
import com.example.webhook_relay.dto.DeliveryResponse;
import com.example.webhook_relay.service.DeliveryQueryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeliveryUiController.class)
class DeliveryUiControllerTest {

    private static final String BASE_URL = "/ui/deliveries";

    private static final Sort EXPECTED_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt")
                    .and(Sort.by(Sort.Direction.DESC, "id"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeliveryQueryService deliveryQueryService;

    @BeforeEach
    void setUp() {
        when(deliveryQueryService.getDeliveries(
                nullable(String.class),
                nullable(UUID.class),
                any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            return Page.empty(pageable);
        });
    }

    @Test
    void shouldRenderDeliveryHistoryWithDefaultParameters()
            throws Exception {

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("deliveries/list"))
                .andExpect(model().attributeExists("deliveries"))
                .andExpect(model().attribute("selectedStatus", "ALL"))
                .andExpect(model().attribute("pageSize", 10))
                .andExpect(model().attribute(
                        "statuses",
                        List.of(
                                "PENDING",
                                "IN_PROGRESS",
                                "SUCCEEDED",
                                "DEAD"
                        )
                ))
                .andExpect(content().string(
                        containsString("Delivery History")
                ));

        verify(deliveryQueryService).getDeliveries(
                isNull(),
                isNull(),
                eq(PageRequest.of(0, 10, EXPECTED_SORT))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "all", "", "   "})
    void shouldTreatAllAndBlankStatusAsUnfiltered(String status)
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("status", status))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "selectedStatus", "ALL"
                ));

        verify(deliveryQueryService).getDeliveries(
                isNull(),
                isNull(),
                any(Pageable.class)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PENDING",
            "IN_PROGRESS",
            "SUCCEEDED",
            "DEAD"
    })
    void shouldSupportAllValidStatusFilters(String status)
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("status", status))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "selectedStatus", status
                ));

        verify(deliveryQueryService).getDeliveries(
                eq(status),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void shouldNormalizeLowercaseStatus()
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("status", " dead "))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "selectedStatus", "DEAD"
                ));

        verify(deliveryQueryService).getDeliveries(
                eq("DEAD"),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void shouldPreserveFiltersAndPagination()
            throws Exception {

        UUID endpointId = UUID.randomUUID();

        mockMvc.perform(get(BASE_URL)
                        .param("status", "SUCCEEDED")
                        .param("endpointId", endpointId.toString())
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "selectedStatus", "SUCCEEDED"
                ))
                .andExpect(model().attribute(
                        "endpointId", endpointId
                ))
                .andExpect(model().attribute("pageSize", 20));

        verify(deliveryQueryService).getDeliveries(
                eq("SUCCEEDED"),
                eq(endpointId),
                eq(PageRequest.of(2, 20, EXPECTED_SORT))
        );
    }

    @Test
    void shouldRejectInvalidStatus()
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("status", "FAILED"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryQueryService);
    }

    @Test
    void shouldRejectNegativePage()
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryQueryService);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void shouldRejectInvalidPageSize(int size)
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("size", String.valueOf(size)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryQueryService);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void shouldAcceptValidPageSizeBoundaries(int size)
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", size));

        verify(deliveryQueryService).getDeliveries(
                isNull(),
                isNull(),
                eq(PageRequest.of(0, size, EXPECTED_SORT))
        );
    }

    @Test
    void shouldRejectInvalidEndpointId()
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("endpointId", "invalid-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryQueryService);
    }

    @Test
    void shouldRenderEmptyState()
            throws Exception {

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("No deliveries found")
                ))
                .andExpect(content().string(
                        containsString("Reset filters")
                ));
    }

    @Test
    void shouldHandleOutOfRangePage()
            throws Exception {

        mockMvc.perform(get(BASE_URL)
                        .param("page", "999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("No deliveries found")
                ));

        verify(deliveryQueryService).getDeliveries(
                isNull(),
                isNull(),
                eq(PageRequest.of(999999, 10, EXPECTED_SORT))
        );
    }

    @Test
    void shouldRenderDeliveryData()
            throws Exception {

        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        Instant timestamp =
                Instant.parse("2026-11-24T21:20:44Z");

        DeliveryResponse delivery = new DeliveryResponse(
                deliveryId,
                UUID.randomUUID(),
                endpointId,
                "SUCCEEDED",
                2,
                timestamp,
                200,
                null,
                timestamp,
                timestamp
        );

        Pageable pageable =
                PageRequest.of(0, 10, EXPECTED_SORT);

        Page<DeliveryResponse> result =
                new PageImpl<>(
                        List.of(delivery),
                        pageable,
                        1
                );

        when(deliveryQueryService.getDeliveries(
                isNull(),
                isNull(),
                eq(pageable)
        )).thenReturn(result);

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "deliveries", result
                ))
                .andExpect(content().string(
                        containsString(deliveryId.toString())
                ))
                .andExpect(content().string(
                        containsString(endpointId.toString())
                ))
                .andExpect(content().string(
                        containsString("status-succeeded")
                ))
                .andExpect(content().string(
                        containsString("2026-11-24 21:20:44")
                ));
    }

    @Test
    void shouldRenderPaginationLinksWithActiveFilters()
            throws Exception {

        UUID endpointId = UUID.randomUUID();

        Instant timestamp =
                Instant.parse("2026-11-24T21:20:44Z");

        DeliveryResponse delivery = new DeliveryResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                endpointId,
                "DEAD",
                5,
                timestamp,
                503,
                "Service unavailable",
                timestamp,
                timestamp
        );

        Pageable pageable =
                PageRequest.of(1, 1, EXPECTED_SORT);

        Page<DeliveryResponse> result =
                new PageImpl<>(
                        List.of(delivery),
                        pageable,
                        3
                );

        when(deliveryQueryService.getDeliveries(
                eq("DEAD"),
                eq(endpointId),
                eq(pageable)
        )).thenReturn(result);

        mockMvc.perform(get(BASE_URL)
                        .param("status", "DEAD")
                        .param("endpointId", endpointId.toString())
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Page 2 of 3")
                ))
                .andExpect(content().string(
                        containsString("status=DEAD")
                ))
                .andExpect(content().string(
                        containsString("endpointId=" + endpointId)
                ))
                .andExpect(content().string(
                        containsString("page=0")
                ))
                .andExpect(content().string(
                        containsString("page=2")
                ))
                .andExpect(content().string(
                        containsString("size=1")
                ));
    }
}
