package com.ecommerce.notification.service;

import com.ecommerce.notification.entity.EmailLog;
import com.ecommerce.notification.entity.EmailStatus;
import com.ecommerce.notification.repository.EmailLogRepository;
import com.ecommerce.notification.service.impl.EmailServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock private EmailLogRepository emailLogRepository;

    @InjectMocks
    private EmailServiceImpl emailService;

    @Test
    void sendPaymentConfirmation_logsSentEmail() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        emailService.sendPaymentConfirmation(eventId, orderId, new BigDecimal("29.99"));

        ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(captor.capture());
        EmailLog saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(saved.getSubject()).contains(orderId.toString());
    }

    @Test
    void sendOrderConfirmation_logsSentEmail() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        emailService.sendOrderConfirmation(eventId, orderId);

        ArgumentCaptor<EmailLog> captor = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Order " + orderId + " confirmed");
    }
}
