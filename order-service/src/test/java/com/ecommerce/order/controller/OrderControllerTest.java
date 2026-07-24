package com.ecommerce.order.controller;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.security.SecurityConfig;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private OrderService orderService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createOrder_returns201() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));
        var response = new OrderResponse(UUID.randomUUID(), request.customerId(), OrderStatus.CONFIRMED,
                BigDecimal.TEN, List.of(), null, null);
        when(orderService.createOrder(any(), any())).thenReturn(IdempotentResult.created(response));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createOrder_returns200_whenIdempotencyKeyReplayed() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));
        var response = new OrderResponse(UUID.randomUUID(), request.customerId(), OrderStatus.CONFIRMED,
                BigDecimal.TEN, List.of(), null, null);
        when(orderService.createOrder(any(), any())).thenReturn(IdempotentResult.replayed(response));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "already-seen-key")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void createOrder_returns403_forNonCustomerRole() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createOrder_returns401_whenUnauthenticated() throws Exception {
        // No @WithMockUser: the OAuth2 resource server's BearerTokenAuthenticationEntryPoint
        // recognizes the anonymous principal as "not authenticated" and returns 401 with a
        // WWW-Authenticate header, rather than treating it as a plain role mismatch (403).
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createOrder_returns400_whenNoItems() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void getOrder_returns404_whenMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(orderId)).thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId))
                .andExpect(status().isNotFound());
    }
}
