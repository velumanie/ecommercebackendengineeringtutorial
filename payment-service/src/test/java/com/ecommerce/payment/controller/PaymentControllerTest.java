package com.ecommerce.payment.controller;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.payment.dto.AuthorizePaymentRequest;
import com.ecommerce.payment.dto.PaymentAuthorizationResponse;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.security.SecurityConfig;
import com.ecommerce.payment.service.PaymentService;
import tools.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void authorize_returns200_forCustomer() throws Exception {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"));
        when(paymentService.authorize(any(), any())).thenReturn(
                IdempotentResult.created(new PaymentAuthorizationResponse(UUID.randomUUID(), true, null)));

        mockMvc.perform(post("/api/v1/payments/authorize")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorized").value(true));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void authorize_returns403_forManager() throws Exception {
        var request = new AuthorizePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("49.99"));

        mockMvc.perform(post("/api/v1/payments/authorize")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void authorize_returns400_onInvalidAmount() throws Exception {
        // amount must be >= 0.00 (see AuthorizePaymentRequest) — 0 itself is legitimate, only
        // negative amounts should fail validation.
        mockMvc.perform(post("/api/v1/payments/authorize")
                        .contentType("application/json")
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"customerId\":\"" + UUID.randomUUID() + "\",\"amount\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void get_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.get(id)).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/api/v1/payments/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void refund_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", id))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void refund_returns403_forCustomer() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/{id}/refund", id))
                .andExpect(status().isForbidden());
    }
}
