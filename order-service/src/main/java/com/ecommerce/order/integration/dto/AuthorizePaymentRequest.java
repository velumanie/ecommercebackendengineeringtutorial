package com.ecommerce.order.integration.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AuthorizePaymentRequest(UUID orderId, UUID customerId, BigDecimal amount) {
}
