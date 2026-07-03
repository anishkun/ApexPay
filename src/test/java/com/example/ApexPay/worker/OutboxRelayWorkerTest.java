package com.example.ApexPay.worker;

import com.example.ApexPay.config.OutboxProperties;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.enums.OutboxStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.example.ApexPay.service.OutboxRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the resilient outbox relay logic without a live broker: an eligible
 * event whose publish the broker confirms is marked PUBLISHED exactly once; a
 * publish that is NOT confirmed records a retryable failure with backoff. The
 * real-broker path is covered by OutboxRabbitIntegrationTest.
 */
class OutboxRelayWorkerTest {

    private OutboxEvent newPendingEvent(ObjectMapper mapper) throws Exception {
        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.50"));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(mapper.writeValueAsString(payload))
                .status(OutboxStatus.PENDING)
                .processed(false)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedPublishMarksEventPublishedExactlyOnce() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();

        OutboxEvent event = newPendingEvent(mapper);
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        // Broker confirms the publish.
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.TRUE);

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props, new SimpleMeterRegistry());
        int published = service.relayBatch();

        assertEquals(1, published);
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertTrue(event.isProcessed(), "processed flag should track status==PUBLISHED");
        assertNotNull(event.getPublishedAt());
        verify(repo).save(event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unconfirmedPublishRecordsRetryableFailureWithBackoff() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();

        OutboxEvent event = newPendingEvent(mapper);
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        // Broker did NOT confirm (NACK / timeout).
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.FALSE);

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props, new SimpleMeterRegistry());
        int published = service.relayBatch();

        assertEquals(0, published);
        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertFalse(event.isProcessed());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getNextAttemptAt(), "a retry time must be scheduled");
        verify(repo).save(event);
    }
}
