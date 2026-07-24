package com.ecommerce.notification.repository;

import com.ecommerce.notification.entity.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {
    boolean existsByEventTypeAndSourceId(String eventType, UUID sourceId);
}
