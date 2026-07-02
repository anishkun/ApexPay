package com.example.ApexPay.worker;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxRelayWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper; // ADDED: To read the JSON string
    private final ObservationRegistry observationRegistry;

    // Metrics (registered eagerly at construction so they appear in /actuator/prometheus at zero)
    private final Counter publishedCounter;
    private final Counter publishFailedCounter;
    private final Counter retriedCounter;
    private final Timer pollTimer;

    // The worker has no persistent retry state: failed events simply stay processed=false and are
    // re-polled on the next cycle. This in-memory set lets us count those re-attempts as retries.
    private final Set<UUID> previouslyFailedEventIds = ConcurrentHashMap.newKeySet();

    public OutboxRelayWorker(OutboxEventRepository outboxEventRepository,
                             RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry,
                             ObservationRegistry observationRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.publishedCounter = Counter.builder("apexpay.outbox.published")
                .description("Outbox events successfully relayed to RabbitMQ")
                .register(meterRegistry);
        this.publishFailedCounter = Counter.builder("apexpay.outbox.publish.failed")
                .description("Outbox event publish attempts that failed")
                .register(meterRegistry);
        this.retriedCounter = Counter.builder("apexpay.outbox.retried")
                .description("Re-attempts of outbox events whose previous publish attempt failed")
                .register(meterRegistry);
        this.pollTimer = Timer.builder("apexpay.outbox.poll")
                .description("Duration of one outbox relay poll cycle")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutboxEvents() {
        long start = System.nanoTime();
        try {
            relayPendingEvents();
        } finally {
            pollTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private void relayPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events. Relaying to RabbitMQ...", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            if (previouslyFailedEventIds.remove(event.getId())) {
                retriedCounter.increment();
            }
            // Scheduler thread has no request context: restore the correlation ID
            // persisted on the outbox row so relay logs join the payment's lifecycle.
            String correlationId = event.getCorrelationId();
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                // Root the publish in its own Observation so it gets a span even though
                // the scheduler thread has no HTTP trace context. The outbox hop
                // intentionally breaks the trace (design decision): the HTTP trace,
                // this relay trace and the consumer trace are joined by correlationId.
                // If the observation machinery ever throws, the surrounding catch marks
                // the event for retry -- an event can be delayed but never lost.
                Observation.createNotStarted("apexpay.outbox.relay", observationRegistry)
                        .lowCardinalityKeyValue("event.type", event.getEventType())
                        .observeChecked(() -> publishEvent(event, correlationId));

                event.setProcessed(true);
                outboxEventRepository.save(event);

                publishedCounter.increment();
                log.info("Successfully relayed event ID: {}", event.getId());

            } catch (Exception e) {
                publishFailedCounter.increment();
                previouslyFailedEventIds.add(event.getId());
                log.error("Failed to relay outbox event {}. Will retry. Error: {}", event.getId(), e.getMessage());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private void publishEvent(OutboxEvent event, String correlationId) throws Exception {
        String routingKey = "transaction.success.standard";

        // FIX: Convert the String payload back to an Object before sending
        TransactionSuccessEvent payloadObject = objectMapper.readValue(
                event.getPayload(),
                TransactionSuccessEvent.class
        );

        // Hand the Object to RabbitMQ (Jackson will serialize it properly now),
        // stamping the correlation ID onto the AMQP message so the consumer
        // (possibly another process) can continue the same trail. With
        // spring.rabbitmq.template.observation-enabled=true the template also adds a
        // send span (child of apexpay.outbox.relay) and a W3C traceparent header.
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, payloadObject, message -> {
            if (correlationId != null) {
                message.getMessageProperties().setHeader(CorrelationIdFilter.AMQP_HEADER, correlationId);
                message.getMessageProperties().setCorrelationId(correlationId);
            }
            return message;
        });
    }
}
