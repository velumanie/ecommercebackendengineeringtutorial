package com.ecommerce.notification.repository;

import com.ecommerce.notification.entity.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {
    Page<EmailLog> findByRecipient(String recipient, Pageable pageable);
}
