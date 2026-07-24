package com.ecommerce.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OrderItemRequest(
        @NotNull UUID productId,
        @Min(1) @Max(999) int quantity) {
}
