package com.ecommerce.payment.event;

import com.ecommerce.common.constants.CorrelationIdConstants;
import com.ecommerce.common.event.PaymentCompletedEvent;
import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.payment.entity.OutboxEvent;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes to the outbox table rather than publishing to Kafka directly — see
 * {@link com.ecommerce.payment.entity.OutboxEvent} for why. The insert here runs inside the
 * same {@code @Transactional} business method (authorize) that saves the payment itself, so
 * both commit atomically; {@link OutboxPublisher} is what actually talks to Kafka.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private static final String TOPIC = "payment-events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishCompleted(Payment payment) {
        var event = new PaymentCompletedEvent(payment.getId(), payment.getOrderId(), payment.getAmount(), Instant.now());
        write(payment.getOrderId(), "PaymentCompletedEvent", event);
    }

    public void publishFailed(Payment payment, String reason) {
        var event = new PaymentFailedEvent(payment.getId(), payment.getOrderId(), reason, Instant.now());
        write(payment.getOrderId(), "PaymentFailedEvent", event);
    }

    private void write(UUID orderId, String eventType, Object event) {
        outboxEventRepository.save(OutboxEvent.of("Payment", orderId, eventType, TOPIC,
                objectMapper.writeValueAsString(event), MDC.get(CorrelationIdConstants.MDC_KEY)));
    }
}
