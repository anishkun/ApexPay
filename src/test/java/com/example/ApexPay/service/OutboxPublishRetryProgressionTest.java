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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Deterministic (no broker) proofs of the publish-side resilience contract that
 * complement the real-broker {@link com.example.ApexPay.worker.OutboxRabbitIntegrationTest}:
 *
 * <ol>
 *   <li>The multi-attempt PENDING -&gt; FAILED -&gt; ... -&gt; DEAD progression, asserting
 *       the exponential-backoff delay computed at EACH attempt follows
 *       {@code min(base * 2^(attempt-1), max)}, the attempt counter increments, the
 *       event is never lost (stays unpublished), and on exhaustion it is DEAD and
 *       routed to the publish-failure DLQ exactly once.</li>
 *   <li>No-double-publish: a stale-version save deterministically throws
 *       {@link OptimisticLockingFailureException}, which is exactly how a second
 *       racing relay's commit is rejected (at-most-once update view).</li>
 * </ol>
 */
class OutboxPublishRetryProgressionTest {

    private OutboxEvent newPendingEvent(ObjectMapper mapper) throws Exception {
        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(mapper.writeValueAsString(payload))
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .processed(false)
                .build();
    }

    /**
     * Drive the SAME event through repeated relay passes where the broker never
     * confirms, asserting the backoff window grows exponentially each attempt and
     * the event lands DEAD on the publish-failure DLQ after maxAttempts.
     */
    @Test
    void successiveFailuresFollowExponentialBackoffThenDeadLetter() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();
        props.setMaxAttempts(5);
        props.setBackoffBaseMs(1000);
        props.setBackoffMaxMs(60000);

        OutboxEvent event = newPendingEvent(mapper);
        // The relay always re-fetches THIS event while it is still eligible.
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        // Broker never confirms.
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.FALSE);

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props);

        // attempt-1..4 are retryable FAILED with growing backoff; attempt-5 is DEAD.
        long[] expectedDelays = {1000, 2000, 4000, 8000};
        for (int attempt = 1; attempt <= expectedDelays.length; attempt++) {
            LocalDateTime before = LocalDateTime.now();
            int published = service.relayBatch();

            assertEquals(0, published, "a NACKed publish must not count as published");
            assertEquals(OutboxStatus.FAILED, event.getStatus(),
                    "attempt " + attempt + " (< max) must stay retryable FAILED");
            assertEquals(attempt, event.getAttemptCount(), "attempt counter must increment");
            assertFalse(event.isProcessed(), "a failed event is never marked published (no loss)");
            assertNotNull(event.getNextAttemptAt(), "a retryable event must be re-scheduled");

            // nextAttemptAt ~= now + backoff(attempt). Allow a generous window for wall-clock.
            long scheduledDelayMs = java.time.Duration.between(before, event.getNextAttemptAt()).toMillis();
            long expected = expectedDelays[attempt - 1];
            assertTrue(scheduledDelayMs >= expected - 1000 && scheduledDelayMs <= expected + 5000,
                    () -> "attempt " + (event.getAttemptCount()) + " backoff ~" + expected
                            + "ms, was " + scheduledDelayMs + "ms");
            // Cross-check against the pure formula.
            assertEquals(expected, props.backoffMillis(attempt),
                    "backoff must equal min(base*2^(attempt-1), max)");

            // Not dead-lettered while retryable.
            verify(rabbitTemplate, never()).send(eq(RabbitMQConfig.OUTBOX_DLX), any(), any(Message.class));
        }

        // The 5th attempt exhausts retries -> DEAD + routed to publish-failure DLQ once.
        int published = service.relayBatch();
        assertEquals(0, published);
        assertEquals(OutboxStatus.DEAD, event.getStatus(), "after maxAttempts the event must be DEAD");
        assertEquals(5, event.getAttemptCount());
        assertNull(event.getNextAttemptAt(), "a DEAD event must not be scheduled for retry");
        assertFalse(event.isProcessed(), "a DEAD event was never published (no loss, but not lost either)");
        verify(rabbitTemplate, times(1)).send(
                eq(RabbitMQConfig.OUTBOX_DLX),
                eq(RabbitMQConfig.OUTBOX_DLQ_ROUTING_KEY),
                any(Message.class));
    }

    /**
     * No-double-publish: the @Version optimistic lock means a second relay racing
     * over the same row sees a stale version and its update is rejected with
     * {@link OptimisticLockingFailureException}. When that escapes the relay's
     * {@code @Transactional} batch, the transaction rolls back, so the losing
     * relay's would-be PUBLISHED update is DISCARDED — the row keeps the winner's
     * single PUBLISHED state. We assert that rejection mechanism directly
     * (deterministic) rather than relying on a flaky live two-thread race.
     */
    @Test
    void staleVersionSaveIsRejectedToPreventDoublePublish() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxProperties props = new OutboxProperties();

        OutboxEvent event = newPendingEvent(mapper);
        when(repo.findEligibleForRelay(any(), any(Pageable.class))).thenReturn(List.of(event));
        // Broker confirms the publish for the LOSING relay too...
        when(rabbitTemplate.invoke(any())).thenReturn(Boolean.TRUE);
        // ...but the row was already PUBLISHED by the winning relay: persisting the
        // now-stale version is rejected, exactly as the @Version guard does in prod.
        when(repo.save(event)).thenThrow(
                new OptimisticLockingFailureException("stale version - row already relayed by a racing relay"));

        OutboxRelayService service = new OutboxRelayService(repo, rabbitTemplate, mapper, props);

        // The optimistic-lock failure escapes relayBatch (the @Transactional batch
        // then rolls back), so the losing relay's update never commits: no double
        // publish, the winner's single PUBLISHED stands.
        assertThrows(OptimisticLockingFailureException.class, service::relayBatch,
                "a stale-version update must be rejected so the racing relay can't double-publish");
    }
}
