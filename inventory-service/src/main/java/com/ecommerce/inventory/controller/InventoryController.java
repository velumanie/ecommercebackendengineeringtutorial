package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.ReserveStockRequest;
import com.ecommerce.inventory.dto.SetStockRequest;
import com.ecommerce.inventory.dto.StockAvailabilityResponse;
import com.ecommerce.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productId}/availability")
    public StockAvailabilityResponse checkAvailability(@PathVariable UUID productId,
                                                         @RequestParam int quantity) {
        return inventoryService.checkAvailability(productId, quantity);
    }

    @PostMapping("/{productId}/stock")
    public StockAvailabilityResponse setStock(@PathVariable UUID productId,
                                               @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(productId, request.quantityOnHand());
    }

    @PostMapping("/{productId}/reserve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@PathVariable UUID productId, @Valid @RequestBody ReserveStockRequest request) {
        inventoryService.reserve(productId, request.quantity());
    }

    @PostMapping("/{productId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID productId, @Valid @RequestBody ReserveStockRequest request) {
        inventoryService.release(productId, request.quantity());
    }
}
