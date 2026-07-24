package com.ecommerce.common.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailedEvent(UUID paymentId, UUID orderId, String reason, Instant occurredAt) {
}
