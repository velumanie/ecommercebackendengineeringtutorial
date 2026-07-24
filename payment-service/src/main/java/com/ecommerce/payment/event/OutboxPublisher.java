package com.ecommerce.payment.event;

import com.ecommerce.common.constants.CorrelationIdConstants;
import com.ecommerce.payment.entity.OutboxEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Polls the outbox table and hands each unpublished row to Kafka. Deliberately not
 * {@code @Transactional} at the method level — each row's publish-then-mark-published is its
 * own short-lived operation via the repository's own transactional {@code save}, so one slow
 * or failed send doesn't hold a DB transaction open across the whole batch. A row that fails
 * to send simply stays unpublished and is retried on the next poll.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;

    @Scheduled(fixedDelay = 500)
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                var record = new ProducerRecord<>(event.getTopic(), null, event.getAggregateId().toString(), event.getPayload());
                if (event.getCorrelationId() != null) {
                    record.headers().add(CorrelationIdConstants.HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }
                outboxKafkaTemplate.send(record).get();
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} ({} for {} {}) — will retry next poll",
                        event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(), e);
            }
        }
    }
}
