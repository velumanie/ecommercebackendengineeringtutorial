package com.ecommerce.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCompletedEvent(UUID paymentId, UUID orderId, BigDecimal amount, Instant occurredAt) {
}
