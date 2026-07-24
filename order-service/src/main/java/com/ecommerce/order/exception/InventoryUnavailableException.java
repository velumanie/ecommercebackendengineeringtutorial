package com.ecommerce.order.exception;

import java.util.UUID;

public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(UUID productId, Throwable cause) {
        super("Inventory service unavailable for product: " + productId, cause);
    }

    public InventoryUnavailableException(UUID productId, int requested, int available) {
        super("Insufficient stock for product %s: requested %d, available %d"
                .formatted(productId, requested, available));
    }
}
