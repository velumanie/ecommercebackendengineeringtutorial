package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.StockAvailabilityResponse;

import java.util.UUID;

public interface InventoryService {
    StockAvailabilityResponse checkAvailability(UUID productId, int quantity);
    void reserve(UUID productId, int quantity);
    void release(UUID productId, int quantity);

    /**
     * Creates or overwrites the on-hand quantity for a product at the default warehouse.
     * The only way stock enters the system at all — receiving new inventory, correcting a
     * count, or onboarding a brand-new product — since {@link #reserve} only ever
     * decrements an existing row.
     */
    StockAvailabilityResponse setStock(UUID productId, int quantityOnHand);
}
