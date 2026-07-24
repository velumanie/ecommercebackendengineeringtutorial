package com.ecommerce.payment.dto;

import com.ecommerce.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id, UUID orderId, UUID customerId, PaymentStatus status,
        BigDecimal amount, String method, Instant createdAt) {
}
