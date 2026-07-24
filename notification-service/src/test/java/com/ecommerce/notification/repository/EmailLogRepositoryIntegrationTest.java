package com.ecommerce.notification.repository;

import com.ecommerce.notification.entity.EmailLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
class EmailLogRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notifications_test");

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Test
    void savesAndFindsByRecipient() {
        emailLogRepository.save(EmailLog.queue(UUID.randomUUID(), "jane@example.com", "Order confirmed"));
        emailLogRepository.save(EmailLog.queue(UUID.randomUUID(), "john@example.com", "Order confirmed"));

        var page = emailLogRepository.findByRecipient("jane@example.com", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getRecipient()).isEqualTo("jane@example.com");
    }
}
