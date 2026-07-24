package com.ecommerce.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox: written in the same DB transaction as the payment it describes, so
 * the two either both commit or both roll back — no dual-write gap where a crash between
 * "save the payment" and "publish to Kafka" would silently drop the event.
 * {@link com.ecommerce.payment.event.OutboxPublisher} polls unpublished rows and sends them.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    // Captured from MDC at write time (see PaymentEventProducer), not read again from MDC at
    // publish time — OutboxPublisher runs on a scheduler thread with no relation to the HTTP
    // request that created this row, so the only way to carry the original request's
    // correlation id across that async gap is to persist it here and re-attach it as a Kafka
    // header when the row is actually sent.
    @Column(name = "correlation_id")
    private String correlationId;

    public static OutboxEvent of(String aggregateType, UUID aggregateId, String eventType, String topic,
                                  String payload, String correlationId) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.correlationId = correlationId;
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
