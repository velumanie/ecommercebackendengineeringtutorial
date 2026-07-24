package com.ecommerce.inventory.dto;

import java.util.UUID;

public record StockAvailabilityResponse(UUID productId, boolean available, int quantityOnHand) {
}
