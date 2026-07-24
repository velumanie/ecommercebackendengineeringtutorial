package com.ecommerce.notification.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface EmailService {
    void sendPaymentConfirmation(UUID eventId, UUID orderId, BigDecimal amount);
    void sendOrderConfirmation(UUID eventId, UUID orderId);
}
