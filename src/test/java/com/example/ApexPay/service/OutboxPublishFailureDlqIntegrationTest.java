package com.example.ApexPay.service;

import com.example.ApexPay.config.OutboxProperties;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.enums.OutboxStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * REAL-broker proof of the PUBLISH-side dead-letter path: when the broker cannot
 * confirm a publish, the relay retries with exponential backoff and, after
 * {@code maxAttempts}, marks the event DEAD and routes it to the durable
 * publish-failure DLQ {@link RabbitMQConfig#OUTBOX_DLQ} — drained here from the
 * real broker to prove the event landed there (never lost).
 *
 * <h2>How the publish failure is simulated against a real broker</h2>
 * Deterministically forcing a real broker to NACK an otherwise-valid publish is
 * awkward, so this test builds a dedicated {@link OutboxRelayService} wired with
 * a Mockito {@code spy} over the real autowired {@link RabbitTemplate}: only the
 * CONFIRM path ({@code invoke(...)} -> {@code waitForConfirms}) is stubbed to
 * return "not confirmed", while {@code send(...)} (used by the relay's
 * dead-letter step) stays the REAL method and talks to the live broker. So the
 * retry/backoff/DEAD state transitions are exercised exactly as in production,
 * and the dead-letter message genuinely traverses the real
 * {@link RabbitMQConfig#OUTBOX_DLX} -> {@link RabbitMQConfig#OUTBOX_DLQ} topology.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublishFailureDlqIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
    }

    @Autowired
    private RabbitTemplate realRabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ObjectMapper objectMapper;

    private OutboxEvent persistableEvent() throws Exception {
        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("77.00"));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(objectMapper.writeValueAsString(payload))
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .processed(false)
                .build();
    }

    @Test
    void unconfirmedPublishRetriesThenDeadLettersToOutboxDlqOnRealBroker() throws Exception {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RabbitMQConfig.OUTBOX_DLQ, false);

        // An in-memory repo stand-in: we drive relayOne directly so we can observe
        // each attempt's state without a DB. (The DB persistence of these same
        // transitions is covered by the Postgres + H2 unit tests.)
        OutboxProperties props = new OutboxProperties();
        props.setMaxAttempts(3);
        props.setBackoffBaseMs(10);   // tiny so the test doesn't actually wait
        props.setBackoffMaxMs(40);

        // Spy over the real template: confirm path forced to "not confirmed",
        // send() (the dead-letter publish) stays real and hits the live broker.
        RabbitTemplate failing = spy(realRabbitTemplate);
        doReturn(Boolean.FALSE).when(failing).invoke(any());

        // A no-op repo: save() just returns the entity (we assert on the entity itself).
        OutboxEventRepository repo = new InMemoryOutboxRepo();

        OutboxRelayService relay = new OutboxRelayService(repo, failing, objectMapper, props);

        OutboxEvent event = persistableEvent();

        // Attempt 1 + 2: retryable FAILED with growing backoff, NOT dead-lettered yet.
        long[] expectedDelays = {10, 20};
        for (int attempt = 1; attempt <= 2; attempt++) {
            LocalDateTime before = LocalDateTime.now();
            boolean confirmed = relay.relayOne(event);
            assertFalse(confirmed, "unconfirmed publish must report false");
            assertEquals(OutboxStatus.FAILED, event.getStatus());
            assertEquals(attempt, event.getAttemptCount());
            assertFalse(event.isProcessed(), "no loss: never marked published");
            long delay = java.time.Duration.between(before, event.getNextAttemptAt()).toMillis();
            long expected = expectedDelays[attempt - 1];
            assertTrue(delay >= expected - 50 && delay <= expected + 2000,
                    "attempt " + attempt + " backoff ~" + expected + "ms, was " + delay + "ms");
            assertEquals(expected, props.backoffMillis(attempt));
            // Nothing on the DLQ while still retryable.
            assertNull(realRabbitTemplate.receive(RabbitMQConfig.OUTBOX_DLQ, 200),
                    "no DEAD message must reach the DLQ while the event is still retryable");
        }

        // Attempt 3: exhausts retries -> DEAD and routed to the REAL publish-failure DLQ.
        boolean confirmed = relay.relayOne(event);
        assertFalse(confirmed);
        assertEquals(OutboxStatus.DEAD, event.getStatus(), "exhausted event must be DEAD");
        assertEquals(3, event.getAttemptCount());
        assertNull(event.getNextAttemptAt(), "a DEAD event is not rescheduled");
        assertFalse(event.isProcessed());

        // Drain the REAL outbox DLQ: the DEAD event must be there (never lost).
        org.springframework.amqp.core.Message dead =
                realRabbitTemplate.receive(RabbitMQConfig.OUTBOX_DLQ, 10000);
        assertNotNull(dead, "the DEAD outbox event must be routed to the publish-failure DLQ");
        assertEquals(event.getId().toString(),
                dead.getMessageProperties().getHeader("x-outbox-event-id"),
                "the DLQ message must carry the original outbox event id");
        assertEquals(3, ((Number) dead.getMessageProperties().getHeader("x-attempt-count")).intValue(),
                "the DLQ message must record the exhausted attempt count");
    }

    /** Minimal repo: relayOne only calls save(); we assert on the entity itself. */
    static class InMemoryOutboxRepo implements OutboxEventRepository {
        @Override public OutboxEvent save(OutboxEvent e) { return e; }
        // --- everything else unused by relayOne ---
        @Override public <S extends OutboxEvent> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<OutboxEvent> findById(UUID uuid) { return java.util.Optional.empty(); }
        @Override public boolean existsById(UUID uuid) { return false; }
        @Override public java.util.List<OutboxEvent> findAll() { return java.util.List.of(); }
        @Override public java.util.List<OutboxEvent> findAllById(Iterable<UUID> uuids) { return java.util.List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(UUID uuid) { }
        @Override public void delete(OutboxEvent entity) { }
        @Override public void deleteAllById(Iterable<? extends UUID> uuids) { }
        @Override public void deleteAll(Iterable<? extends OutboxEvent> entities) { }
        @Override public void deleteAll() { }
        @Override public <S extends OutboxEvent> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends OutboxEvent> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<OutboxEvent> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) { }
        @Override public void deleteAllInBatch() { }
        @Override public OutboxEvent getOne(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public OutboxEvent getById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public OutboxEvent getReferenceById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public java.util.List<OutboxEvent> findAll(org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
        @Override public org.springframework.data.domain.Page<OutboxEvent> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends OutboxEvent> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return java.util.Optional.empty(); }
        @Override public <S extends OutboxEvent> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { return java.util.List.of(); }
        @Override public <S extends OutboxEvent> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
        @Override public <S extends OutboxEvent> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends OutboxEvent> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends OutboxEvent> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends OutboxEvent, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<OutboxEvent> findPendingEvents() { return java.util.List.of(); }
        @Override public java.util.List<OutboxEvent> findEligibleForRelay(LocalDateTime now, org.springframework.data.domain.Pageable pageable) { return java.util.List.of(); }
        @Override public java.util.List<OutboxEvent> findEligibleForRelaySkipLocked(LocalDateTime now, int batchSize) { return java.util.List.of(); }
    }
}
