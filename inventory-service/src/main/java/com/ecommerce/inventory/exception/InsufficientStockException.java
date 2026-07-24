package com.ecommerce.inventory.exception;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(UUID productId) {
        super("Insufficient stock for product: " + productId);
    }
}
