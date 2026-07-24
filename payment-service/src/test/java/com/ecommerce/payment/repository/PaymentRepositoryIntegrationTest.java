package com.ecommerce.payment.repository;

import com.ecommerce.payment.entity.Payment;
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
class PaymentRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments_test");

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void savesAndFindsPaymentByOrderId() {
        UUID orderId = UUID.randomUUID();
        paymentRepository.save(Payment.create(orderId, UUID.randomUUID(), new BigDecimal("29.99"), null));

        assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
    }

    @Test
    void enforcesUniqueOrderId() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        paymentRepository.saveAndFlush(Payment.create(orderId, customerId, new BigDecimal("10.00"), null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        paymentRepository.saveAndFlush(Payment.create(orderId, customerId, new BigDecimal("15.00"), null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueIdempotencyKey() {
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        paymentRepository.saveAndFlush(Payment.create(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), idempotencyKey));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        paymentRepository.saveAndFlush(Payment.create(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("15.00"), idempotencyKey)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
