package com.ecommerce.order.repository;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders_test");

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void savesAndReloadsOrderWithItems() {
        UUID customerId = UUID.randomUUID();
        OrderItem item = OrderItem.of(UUID.randomUUID(), 3, new BigDecimal("19.99"));
        Order order = Order.create(customerId, java.util.List.of(item), new BigDecimal("59.97"), null);

        Order saved = orderRepository.save(order);

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCustomerId()).isEqualTo(customerId);
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("59.97");
    }
}
