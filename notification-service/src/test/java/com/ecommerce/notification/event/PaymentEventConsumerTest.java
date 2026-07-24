package com.ecommerce.notification.event;

import com.ecommerce.common.event.PaymentCompletedEvent;
import com.ecommerce.notification.entity.NotificationEvent;
import com.ecommerce.notification.repository.NotificationEventRepository;
import com.ecommerce.notification.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock private EmailService emailService;
    @Mock private NotificationEventRepository notificationEventRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentEventConsumer consumer;

    @Test
    void onPaymentCompleted_persistsAndSendsEmail_whenNotSeenBefore() {
        var event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("29.99"), Instant.now());
        when(notificationEventRepository.existsByEventTypeAndSourceId("payment.completed", event.orderId()))
                .thenReturn(false);
        when(notificationEventRepository.saveAndFlush(any(NotificationEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        consumer.onPaymentCompleted(event, "corr-123");

        verify(notificationEventRepository).saveAndFlush(any(NotificationEvent.class));
        verify(emailService).sendPaymentConfirmation(any(), eq(event.orderId()), eq(event.amount()));
    }

    @Test
    void onPaymentCompleted_skips_whenAlreadyProcessed() {
        var event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("29.99"), Instant.now());
        when(notificationEventRepository.existsByEventTypeAndSourceId("payment.completed", event.orderId()))
                .thenReturn(true);

        consumer.onPaymentCompleted(event, "corr-123");

        verify(notificationEventRepository, never()).saveAndFlush(any());
        verify(emailService, never()).sendPaymentConfirmation(any(), any(), any());
    }

    @Test
    void onPaymentCompleted_skips_whenRaceLosesToUniqueConstraint() {
        var event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("29.99"), Instant.now());
        when(notificationEventRepository.existsByEventTypeAndSourceId("payment.completed", event.orderId()))
                .thenReturn(false);
        when(notificationEventRepository.saveAndFlush(any(NotificationEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        consumer.onPaymentCompleted(event, "corr-123");

        verify(emailService, never()).sendPaymentConfirmation(any(), any(), any());
    }

    @Test
    void onPaymentCompleted_restoresCorrelationIdIntoMdc_thenClearsItAfterProcessing() {
        var event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("29.99"), Instant.now());
        when(notificationEventRepository.existsByEventTypeAndSourceId("payment.completed", event.orderId()))
                .thenReturn(false);
        when(notificationEventRepository.saveAndFlush(any(NotificationEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        String[] mdcDuringProcessing = new String[1];
        org.mockito.Mockito.doAnswer(inv -> {
            mdcDuringProcessing[0] = MDC.get("correlationId");
            return null;
        }).when(emailService).sendPaymentConfirmation(any(), any(), any());

        consumer.onPaymentCompleted(event, "corr-456");

        assertThat(mdcDuringProcessing[0]).isEqualTo("corr-456");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void onPaymentCompleted_worksWithoutCorrelationHeader() {
        var event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("29.99"), Instant.now());
        when(notificationEventRepository.existsByEventTypeAndSourceId("payment.completed", event.orderId()))
                .thenReturn(false);
        when(notificationEventRepository.saveAndFlush(any(NotificationEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        consumer.onPaymentCompleted(event, null);

        verify(emailService).sendPaymentConfirmation(any(), eq(event.orderId()), eq(event.amount()));
        assertThat(MDC.get("correlationId")).isNull();
    }
}
