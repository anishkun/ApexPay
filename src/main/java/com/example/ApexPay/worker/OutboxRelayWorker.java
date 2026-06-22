package com.example.ApexPay.worker;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "apexpay.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper; // ADDED: To read the JSON string

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events. Relaying to RabbitMQ...", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                String routingKey = "transaction.success.standard";

                // FIX: Convert the String payload back to an Object before sending
                TransactionSuccessEvent payloadObject = objectMapper.readValue(
                        event.getPayload(),
                        TransactionSuccessEvent.class
                );

                // Hand the Object to RabbitMQ (Jackson will serialize it properly now)
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, payloadObject);

                event.setProcessed(true);
                outboxEventRepository.save(event);

                log.info("Successfully relayed event ID: {}", event.getId());

            } catch (Exception e) {
                log.error("Failed to relay outbox event {}. Will retry. Error: {}", event.getId(), e.getMessage());
            }
        }
    }
}