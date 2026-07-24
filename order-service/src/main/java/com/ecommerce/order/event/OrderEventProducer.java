package com.ecommerce.order.event;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.OrderCreatedEvent;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.common.constants.CorrelationIdConstants;
import com.ecommerce.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes to the outbox table rather than publishing to Kafka directly — see
 * {@link com.ecommerce.order.entity.OutboxEvent} for why. The insert here runs inside the same
 * {@code @Transactional} business method (createOrder/cancelOrder) that saves the order itself,
 * so both commit atomically; {@link OutboxPublisher} is what actually talks to Kafka.
 */
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final String TOPIC = "order-events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishCreated(Order order) {
        var items = order.getItems().stream()
                .map(this::toEventItem)
                .toList();
        var event = new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getTotalAmount(),
                items, Instant.now());
        write(order.getId(), "OrderCreatedEvent", event);
    }

    public void publishCancelled(Order order, String reason) {
        var event = new OrderCancelledEvent(order.getId(), order.getCustomerId(), reason, Instant.now());
        write(order.getId(), "OrderCancelledEvent", event);
    }

    private void write(UUID orderId, String eventType, Object event) {
        outboxEventRepository.save(OutboxEvent.of("Order", orderId, eventType, TOPIC,
                objectMapper.writeValueAsString(event), MDC.get(CorrelationIdConstants.MDC_KEY)));
    }

    private OrderCreatedEvent.Item toEventItem(OrderItem item) {
        return new OrderCreatedEvent.Item(item.getProductId(), item.getQuantity(), item.getUnitPrice());
    }
}
