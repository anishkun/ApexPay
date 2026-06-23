package com.example.ApexPay.service;

import com.example.ApexPay.config.OutboxProperties;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.enums.OutboxStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Proves the publish-failure path: when an event exhausts {@code maxAttempts} it
 * transitions to DEAD and is routed to the publish-failure DLQ exactly once
 * (never lost, never retried again).
 */
class OutboxRelayDeadLetterTest {

    private OutboxEvent newEvent(ObjectMapper mapper, int attemptsSoFar) throws Exception {
        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(mapper.writeValueAsString(payload))
                .status(OutboxStatus.FAILED)
                .attemptCount(attemptsSoFar)
                .processed(false)
                .build();
    }

    @Test
    void exhaustedEventGoesDeadAndIsRoutedToPublishFailureDlq() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();
        props.setMaxAttempts(3);

        // Already failed twice; this attempt (the 3rd) will exhaust retries.
        OutboxEvent event = newEvent(mapper, 2);
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        // Broker never confirms -> publish fails.
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.FALSE);

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props);
        int published = service.relayBatch();

        assertEquals(0, published);
        assertEquals(OutboxStatus.DEAD, event.getStatus());
        assertEquals(3, event.getAttemptCount());
        assertNull(event.getNextAttemptAt(), "a DEAD event must not be scheduled for retry");
        assertFalse(event.isProcessed());

        // Routed to the publish-failure DLQ exactly once.
        verify(rabbitTemplate, times(1)).send(
                eq(RabbitMQConfig.OUTBOX_DLX),
                eq(RabbitMQConfig.OUTBOX_DLQ_ROUTING_KEY),
                any(Message.class));
        verify(repo).save(event);
    }

    @Test
    void belowMaxAttemptsStaysRetryable() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();
        props.setMaxAttempts(5);

        OutboxEvent event = newEvent(mapper, 1); // 2nd attempt now, below the cap
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.FALSE);

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props);
        service.relayBatch();

        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertEquals(2, event.getAttemptCount());
        assertNotNull(event.getNextAttemptAt());
        // Must NOT be dead-lettered yet.
        verify(rabbitTemplate, never()).send(eq(RabbitMQConfig.OUTBOX_DLX), any(), any(Message.class));
    }
}
