package com.ecommerce.inventory.dto;

import jakarta.validation.constraints.Min;

public record SetStockRequest(@Min(0) int quantityOnHand) {
}
