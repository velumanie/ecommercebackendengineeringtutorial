package com.ecommerce.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationEventStatus status = NotificationEventStatus.RECEIVED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static NotificationEvent receive(String eventType, UUID sourceId, String payload) {
        NotificationEvent event = new NotificationEvent();
        event.eventType = eventType;
        event.sourceId = sourceId;
        event.payload = payload;
        return event;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
