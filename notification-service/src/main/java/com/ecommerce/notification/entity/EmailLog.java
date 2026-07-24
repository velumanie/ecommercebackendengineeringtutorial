package com.ecommerce.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_logs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailStatus status = EmailStatus.QUEUED;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static EmailLog queue(UUID eventId, String recipient, String subject) {
        EmailLog log = new EmailLog();
        log.eventId = eventId;
        log.recipient = recipient;
        log.subject = subject;
        return log;
    }

    public void markSent() {
        status = EmailStatus.SENT;
        sentAt = Instant.now();
    }

    public void markFailed() {
        status = EmailStatus.FAILED;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
