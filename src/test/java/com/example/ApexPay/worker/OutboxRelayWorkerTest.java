package com.example.ApexPay.worker;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the outbox relay logic without a live broker: a pending event is
 * deserialized, published with the right exchange/routing key, and marked
 * processed. The real-broker path is covered by OutboxRabbitIntegrationTest.
 */
class OutboxRelayWorkerTest {

    @Test
    void relaysPendingEventToRabbitAndMarksProcessed() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();

        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.50"));
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(mapper.writeValueAsString(payload))
                .processed(false)
                .build();
        when(repo.findPendingEvents()).thenReturn(List.of(event));

        OutboxRelayWorker worker = new OutboxRelayWorker(repo, rabbitTemplate, mapper);
        worker.processOutboxEvents();

        ArgumentCaptor<TransactionSuccessEvent> captor = ArgumentCaptor.forClass(TransactionSuccessEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq("transaction.success.standard"), captor.capture());
        assertEquals(payload.getTransactionId(), captor.getValue().getTransactionId());

        assertTrue(event.isProcessed(), "event should be marked processed after a successful relay");
        verify(repo).save(event);
    }
}
