package com.ecommerce.order.integration;

import com.ecommerce.order.exception.InventoryUnavailableException;
import com.ecommerce.order.integration.dto.ReserveStockRequest;
import com.ecommerce.order.integration.dto.StockAvailabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InventoryClientFallbackFactory implements FallbackFactory<InventoryClient> {

    private static final Logger log = LoggerFactory.getLogger(InventoryClientFallbackFactory.class);

    @Override
    public InventoryClient create(Throwable cause) {
        return new InventoryClient() {
            @Override
            public StockAvailabilityResponse checkAvailability(UUID productId, int quantity) {
                log.warn("inventory-service unavailable while checking availability for {}", productId, cause);
                throw new InventoryUnavailableException(productId, cause);
            }

            @Override
            public void reserve(UUID productId, ReserveStockRequest request) {
                log.warn("inventory-service unavailable while reserving stock for {}", productId, cause);
                throw new InventoryUnavailableException(productId, cause);
            }
        };
    }
}
