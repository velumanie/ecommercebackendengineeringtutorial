package com.ecommerce.order.integration;

import com.ecommerce.order.integration.dto.ReserveStockRequest;
import com.ecommerce.order.integration.dto.StockAvailabilityResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@FeignClient(
        name = "inventory-service",
        configuration = com.ecommerce.order.config.FeignClientConfig.class,
        fallbackFactory = InventoryClientFallbackFactory.class)
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{productId}/availability")
    StockAvailabilityResponse checkAvailability(@PathVariable("productId") UUID productId,
                                                 @RequestParam("quantity") int quantity);

    @PostMapping("/api/v1/inventory/{productId}/reserve")
    void reserve(@PathVariable("productId") UUID productId, @RequestBody ReserveStockRequest request);
}
