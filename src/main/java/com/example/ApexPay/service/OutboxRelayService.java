package com.example.ApexPay.service;

import com.example.ApexPay.config.OutboxProperties;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The resilient transactional-outbox → RabbitMQ relay.
 *
 * <p><b>No loss / no double-publish guarantees:</b>
 * <ul>
 *   <li><b>No loss:</b> an event is only marked PUBLISHED after the broker confirms it
 *       (publisher confirms via {@link RabbitTemplate#invoke}/{@code waitForConfirms}).
 *       A NACK / confirm-timeout / send exception leaves the event un-published so it is
 *       retried; once retries are exhausted it is routed to the publish-failure DLQ
 *       (never simply dropped).</li>
 *   <li><b>No double-publish:</b> the eligible-events poll locks rows, and the
 *       {@code @Version} optimistic lock on {@link OutboxEvent} means that if two relay
 *       instances race, the second commit fails with an optimistic-lock exception and its
 *       update (and therefore its at-most-once view) is rolled back. Downstream consumers
 *       additionally dedupe on the stable message id (= the outbox event UUID), so the
 *       contract is <b>at-least-once</b> delivery with consumer-side idempotency.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties props;

    /** Routing key the outbox uses for standard transaction-success events. */
    static final String ROUTING_KEY = "transaction.success.standard";

    /**
     * Relay one batch of eligible events. Each event is handled independently: a
     * single event's failure never aborts the batch. Safe to invoke repeatedly.
     *
     * @return number of events successfully published this batch.
     */
    @Transactional
    public int relayBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> batch = props.isUseSkipLocked()
                ? outboxEventRepository.findEligibleForRelaySkipLocked(now, props.getBatchSize())
                : outboxEventRepository.findEligibleForRelay(now, PageRequest.of(0, props.getBatchSize()));

        if (batch.isEmpty()) {
            return 0;
        }

        log.info("Relaying {} eligible outbox event(s) to RabbitMQ...", batch.size());
        int published = 0;
        for (OutboxEvent event : batch) {
            if (relayOne(event)) {
                published++;
            }
        }
        return published;
    }

    /**
     * Publish a single event and update its state. Returns true if the broker
     * confirmed the publish (event marked PUBLISHED), false otherwise (FAILED/DEAD).
     */
    boolean relayOne(OutboxEvent event) {
        try {
            TransactionSuccessEvent payload = objectMapper.readValue(
                    event.getPayload(), TransactionSuccessEvent.class);

            boolean confirmed = publishConfirmed(
                    RabbitMQConfig.EXCHANGE, ROUTING_KEY, payload, event.getId().toString());

            if (confirmed) {
                event.markPublished();
                outboxEventRepository.save(event);
                log.info("Relayed outbox event {} (confirmed by broker).", event.getId());
                return true;
            }
            recordFailure(event, "Broker did not confirm publish (NACK or timeout).");
            return false;

        } catch (Exception e) {
            recordFailure(event, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Publish a message with a stable message id and wait for a publisher confirm.
     * Returns true only if the broker ACKed within the configured timeout.
     */
    private boolean publishConfirmed(String exchange, String routingKey, Object payload, String messageId) {
        return Boolean.TRUE.equals(rabbitTemplate.invoke(operations -> {
            CorrelationData correlation = new CorrelationData(messageId);
            operations.convertAndSend(exchange, routingKey, payload, message -> {
                message.getMessageProperties().setMessageId(messageId);
                return message;
            }, correlation);
            return operations.waitForConfirms(props.getConfirmTimeoutMs());
        }));
    }

    /**
     * Apply backoff or dead-letter the event depending on how many attempts it has
     * now consumed.
     */
    private void recordFailure(OutboxEvent event, String error) {
        int attempts = event.getAttemptCount() + 1;
        event.setAttemptCount(attempts);

        if (attempts >= props.getMaxAttempts()) {
            event.markDead(error);
            outboxEventRepository.save(event);
            deadLetter(event);
            log.error("Outbox event {} exhausted {} attempts -> DEAD, routed to publish-failure DLQ. Last error: {}",
                    event.getId(), attempts, error);
        } else {
            long delay = props.backoffMillis(attempts);
            LocalDateTime next = LocalDateTime.now().plusNanos(delay * 1_000_000L);
            event.markFailed(error, next);
            outboxEventRepository.save(event);
            log.warn("Outbox event {} publish failed (attempt {}/{}). Retrying after {} ms. Error: {}",
                    event.getId(), attempts, props.getMaxAttempts(), delay, error);
        }
    }

    /**
     * Route a permanently-failed event to the durable publish-failure DLQ so it is
     * never lost and is visible to ops. The raw payload + diagnostic headers are
     * sent (best-effort; failure to DLQ is logged but does not requeue the event,
     * which is already persisted as DEAD).
     */
    private void deadLetter(OutboxEvent event) {
        try {
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(event.getId().toString());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setHeader("x-outbox-event-id", event.getId().toString());
            properties.setHeader("x-event-type", event.getEventType());
            properties.setHeader("x-attempt-count", event.getAttemptCount());
            properties.setHeader("x-last-error", event.getLastError());
            Message message = new Message(
                    event.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
            rabbitTemplate.send(
                    RabbitMQConfig.OUTBOX_DLX, RabbitMQConfig.OUTBOX_DLQ_ROUTING_KEY, message);
        } catch (Exception e) {
            log.error("Failed to route DEAD outbox event {} to publish-failure DLQ: {}",
                    event.getId(), e.getMessage());
        }
    }
}
