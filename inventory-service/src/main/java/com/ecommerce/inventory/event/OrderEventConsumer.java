package com.ecommerce.inventory.event;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-service reserves stock synchronously (via the Feign call in OrderServiceImpl)
 * so it can reject the order immediately if stock is unavailable. Releasing stock on
 * cancellation doesn't need that same-request answer, so it happens here, asynchronously,
 * reacting to the event order-service publishes.
 */
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final InventoryService inventoryService;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Releasing reserved stock for cancelled order {}", event.orderId());
        // In the full implementation this looks up the order's line items (via a local
        // read model kept in sync from order.created events) to know what to release.
    }
}
