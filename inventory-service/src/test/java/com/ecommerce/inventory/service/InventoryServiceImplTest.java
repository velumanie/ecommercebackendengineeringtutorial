package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.StockAvailabilityResponse;
import com.ecommerce.inventory.entity.Stock;
import com.ecommerce.inventory.entity.Warehouse;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.StockNotFoundException;
import com.ecommerce.inventory.repository.StockRepository;
import com.ecommerce.inventory.repository.WarehouseRepository;
import com.ecommerce.inventory.service.impl.InventoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock private StockRepository stockRepository;
    @Mock private WarehouseRepository warehouseRepository;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private final UUID productId = UUID.randomUUID();
    private final Warehouse warehouse = Warehouse.create("WH-MAIN", "Primary");

    @Test
    void checkAvailability_returnsAvailable_whenEnoughStock() {
        Stock stock = Stock.create(productId, warehouse, 10);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));

        StockAvailabilityResponse response = inventoryService.checkAvailability(productId, 5);

        assertThat(response.available()).isTrue();
        assertThat(response.quantityOnHand()).isEqualTo(10);
    }

    @Test
    void checkAvailability_returnsUnavailable_whenNotEnoughStock() {
        Stock stock = Stock.create(productId, warehouse, 2);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));

        StockAvailabilityResponse response = inventoryService.checkAvailability(productId, 5);

        assertThat(response.available()).isFalse();
    }

    @Test
    void checkAvailability_throws_whenNoStockRecord() {
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.checkAvailability(productId, 1))
                .isInstanceOf(StockNotFoundException.class);
    }

    @Test
    void reserve_increasesReservedQuantity() {
        Stock stock = Stock.create(productId, warehouse, 10);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));

        inventoryService.reserve(productId, 4);

        assertThat(stock.getQuantityReserved()).isEqualTo(4);
        assertThat(stock.available()).isEqualTo(6);
        verify(stockRepository).save(stock);
    }

    @Test
    void reserve_throws_whenInsufficientStock() {
        Stock stock = Stock.create(productId, warehouse, 2);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> inventoryService.reserve(productId, 5))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void release_decreasesReservedQuantity() {
        Stock stock = Stock.create(productId, warehouse, 10);
        stock.reserve(6);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));

        inventoryService.release(productId, 4);

        assertThat(stock.getQuantityReserved()).isEqualTo(2);
    }

    @Test
    void release_isNoOp_whenNoStockRecord() {
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.empty());

        inventoryService.release(productId, 4);

        verify(stockRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setStock_createsNewRecord_atDefaultWarehouse_whenNoneExists() {
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.empty());
        when(warehouseRepository.findByCode("WH-MAIN")).thenReturn(Optional.of(warehouse));
        when(stockRepository.save(org.mockito.ArgumentMatchers.any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        StockAvailabilityResponse response = inventoryService.setStock(productId, 50);

        assertThat(response.quantityOnHand()).isEqualTo(50);
        assertThat(response.available()).isTrue();
    }

    @Test
    void setStock_overwritesQuantityOnHand_whenRecordExists() {
        Stock stock = Stock.create(productId, warehouse, 10);
        stock.reserve(3);
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.of(stock));
        when(stockRepository.save(stock)).thenReturn(stock);

        inventoryService.setStock(productId, 100);

        assertThat(stock.getQuantityOnHand()).isEqualTo(100);
        assertThat(stock.getQuantityReserved()).isEqualTo(3);
    }

    @Test
    void setStock_throws_whenDefaultWarehouseMissing() {
        when(stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId)).thenReturn(Optional.empty());
        when(warehouseRepository.findByCode("WH-MAIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.setStock(productId, 10))
                .isInstanceOf(IllegalStateException.class);
    }
}
