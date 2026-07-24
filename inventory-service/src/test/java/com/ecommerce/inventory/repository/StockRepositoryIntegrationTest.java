package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.entity.Stock;
import com.ecommerce.inventory.entity.Warehouse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_test");

    @Autowired private StockRepository stockRepository;
    @Autowired private WarehouseRepository warehouseRepository;

    @Test
    void savesAndFindsStockByProduct() {
        Warehouse warehouse = warehouseRepository.save(Warehouse.create("WH-TEST", "Test Site"));
        UUID productId = UUID.randomUUID();
        stockRepository.save(Stock.create(productId, warehouse, 25));

        var found = stockRepository.findFirstByProductIdOrderByQuantityOnHandDesc(productId);

        assertThat(found).isPresent();
        assertThat(found.get().available()).isEqualTo(25);
    }
}
