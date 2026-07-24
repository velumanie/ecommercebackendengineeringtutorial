package com.ecommerce.product.repository;

import com.ecommerce.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("products_test");

    @Autowired
    private ProductRepository productRepository;

    @Test
    void savesAndReloadsProduct() {
        Product product = Product.create("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);

        Product saved = productRepository.save(product);

        Product reloaded = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSku()).isEqualTo("SKU-1");
        assertThat(reloaded.getPrice()).isEqualByComparingTo("19.99");
    }
}
