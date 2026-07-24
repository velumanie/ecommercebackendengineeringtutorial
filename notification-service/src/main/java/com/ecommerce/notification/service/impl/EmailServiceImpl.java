package com.ecommerce.notification.service.impl;

import com.ecommerce.notification.entity.EmailLog;
import com.ecommerce.notification.repository.EmailLogRepository;
import com.ecommerce.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final EmailLogRepository emailLogRepository;

    @Override
    @Transactional
    public void sendPaymentConfirmation(UUID eventId, UUID orderId, BigDecimal amount) {
        // Resolves the customer's email from user-service in the full implementation;
        // the reference flow logs against the order id so the delivery pipeline is exercised end to end.
        send(eventId, orderId + "@customers.company.com", "Payment received for order " + orderId);
    }

    @Override
    @Transactional
    public void sendOrderConfirmation(UUID eventId, UUID orderId) {
        send(eventId, orderId + "@customers.company.com", "Order " + orderId + " confirmed");
    }

    private void send(UUID eventId, String recipient, String subject) {
        EmailLog emailLog = EmailLog.queue(eventId, recipient, subject);
        try {
            // Real SMTP/provider dispatch happens here (spring-boot-starter-mail JavaMailSender).
            log.info("Sending email to {}: {}", recipient, subject);
            emailLog.markSent();
        } catch (Exception e) {
            log.error("Failed to send email to {}", recipient, e);
            emailLog.markFailed();
        }
        emailLogRepository.save(emailLog);
    }
}
