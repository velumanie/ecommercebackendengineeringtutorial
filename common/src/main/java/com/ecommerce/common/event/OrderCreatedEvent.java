package com.ecommerce.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        List<Item> items,
        Instant occurredAt) {

    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }
}
