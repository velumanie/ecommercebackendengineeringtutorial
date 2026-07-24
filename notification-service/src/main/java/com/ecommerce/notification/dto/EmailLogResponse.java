package com.ecommerce.notification.dto;

import com.ecommerce.notification.entity.EmailStatus;

import java.time.Instant;
import java.util.UUID;

public record EmailLogResponse(UUID id, String recipient, String subject, EmailStatus status, Instant sentAt) {
}
