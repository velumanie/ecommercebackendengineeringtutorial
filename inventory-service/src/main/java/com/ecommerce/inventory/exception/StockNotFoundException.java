package com.ecommerce.inventory.exception;

import java.util.UUID;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException(UUID productId) {
        super("No stock record for product: " + productId);
    }
}
