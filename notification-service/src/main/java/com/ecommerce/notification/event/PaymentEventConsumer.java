package com.ecommerce.notification.event;

import com.ecommerce.common.constants.CorrelationIdConstants;
import com.ecommerce.common.event.PaymentCompletedEvent;
import com.ecommerce.notification.entity.NotificationEvent;
import com.ecommerce.notification.repository.NotificationEventRepository;
import com.ecommerce.notification.service.EmailService;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String EVENT_TYPE = "payment.completed";

    private final EmailService emailService;
    private final NotificationEventRepository notificationEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "notification-service",
            containerFactory = "paymentEventListenerFactory")
    @Transactional
    @SneakyThrows
    public void onPaymentCompleted(PaymentCompletedEvent event,
            @Header(name = CorrelationIdConstants.HEADER, required = false) String correlationId) {
        // Restores the correlation id the producer captured from the original HTTP request
        // (see OutboxPublisher) so logs on this side of the async Kafka boundary can still be
        // traced back to it, the same way the gateway/service HTTP filters do for a single request.
        if (correlationId != null) {
            MDC.put(CorrelationIdConstants.MDC_KEY, correlationId);
        }
        try {
            // Kafka only guarantees at-least-once delivery; a consumer restart/rebalance can
            // redeliver an already-handled record. Skip fast if we've already processed this
            // order's event...
            if (notificationEventRepository.existsByEventTypeAndSourceId(EVENT_TYPE, event.orderId())) {
                log.info("Skipping duplicate {} event for order {} — already processed", EVENT_TYPE, event.orderId());
                return;
            }

            NotificationEvent record = NotificationEvent.receive(
                    EVENT_TYPE, event.orderId(), objectMapper.writeValueAsString(event));
            try {
                // ...and saveAndFlush + catch the unique-constraint violation to close the race
                // where two redeliveries both pass the check above before either persists.
                notificationEventRepository.saveAndFlush(record);
            } catch (DataIntegrityViolationException e) {
                log.info("Duplicate {} event for order {} raced onto the unique constraint — already processed",
                        EVENT_TYPE, event.orderId());
                return;
            }
            emailService.sendPaymentConfirmation(record.getId(), event.orderId(), event.amount());
        } finally {
            MDC.remove(CorrelationIdConstants.MDC_KEY);
        }
    }
}
