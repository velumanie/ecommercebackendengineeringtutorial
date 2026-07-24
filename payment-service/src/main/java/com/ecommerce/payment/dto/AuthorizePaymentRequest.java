package com.ecommerce.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AuthorizePaymentRequest(
        @NotNull UUID orderId,
        @NotNull UUID customerId,
        // order-service currently prices orders from the request payload only (no product-service
        // price lookup yet — see OrderServiceImpl.reserveAndPrice), so totalAmount can legitimately
        // be 0.00 in this reference build; only reject negative amounts.
        @NotNull @DecimalMin("0.00") BigDecimal amount) {
}
