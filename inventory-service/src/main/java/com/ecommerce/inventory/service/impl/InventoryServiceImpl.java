package com.ecommerce.inventory.service.impl;

import com.ecommerce.inventory.dto.StockAvailabilityResponse;
import com.ecommerce.inventory.entity.Stock;
import com.ecommerce.inventory.entity.Warehouse;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.StockNotFoundException;
import com.ecommerce.inventory.repository.StockRepository;
import com.ecommerce.inventory.repository.WarehouseRepository;
import com.ecommerce.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final String DEFAULT_WAREHOUSE_CODE = "WH-MAIN";

    private final StockRepository stockRepository;
    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional(readOnly = true)
    public StockAvailabilityResponse checkAvailability(UUID productId, int quantity) {
        Stock stock = stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
        return new StockAvailabilityResponse(productId, stock.available() >= quantity, stock.available());
    }

    @Override
    @Transactional
    public void reserve(UUID productId, int quantity) {
        Stock stock = stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
        if (stock.available() < quantity) {
            throw new InsufficientStockException(productId);
        }
        stock.reserve(quantity);
        stockRepository.save(stock);
    }

    @Override
    @Transactional
    public void release(UUID productId, int quantity) {
        stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)
                .ifPresent(stock -> {
                    stock.release(quantity);
                    stockRepository.save(stock);
                });
    }

    @Override
    @Transactional
    public StockAvailabilityResponse setStock(UUID productId, int quantityOnHand) {
        Stock stock = stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)
                .orElseGet(() -> Stock.create(productId, defaultWarehouse(), 0));
        stock.setQuantityOnHand(quantityOnHand);
        Stock saved = stockRepository.save(stock);
        return new StockAvailabilityResponse(productId, saved.available() > 0, saved.available());
    }

    private Warehouse defaultWarehouse() {
        return warehouseRepository.findByCode(DEFAULT_WAREHOUSE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Default warehouse '" + DEFAULT_WAREHOUSE_CODE + "' is missing — check the Flyway seed migration"));
    }
}
