package com.example.ApexPay.worker;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.enums.OutboxStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.example.ApexPay.service.OutboxRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the resilient transactional-outbox → RabbitMQ path against
 * a real broker spun up in Docker. The whole class self-skips when no Docker
 * daemon is available (disabledWithoutDocker = true), so {@code mvn test} stays
 * green offline.
 *
 * <p>Covers: (i) a normal event publishes (broker confirm) and is marked
 * PUBLISHED exactly once, and (ii) the DLQ topology (consumer DLQ + publish-
 * failure DLQ) is declared on the broker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxRabbitIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Publisher confirms are required for the relay to mark events PUBLISHED.
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxRelayService relayService;

    @Test
    void confirmedEventIsDeliveredAndMarkedPublishedExactlyOnce() throws Exception {
        // Declare exchange/queue/binding eagerly so the queue exists before we read.
        rabbitAdmin.initialize();

        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("250.00"));
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(objectMapper.writeValueAsString(payload))
                .status(OutboxStatus.PENDING)
                .processed(false)
                .build();
        outboxEventRepository.save(event);

        // Relay deterministically (the scheduled bean is disabled in tests).
        int published = relayService.relayBatch();
        assertEquals(1, published, "exactly one event should be published");

        Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.AI_QUEUE, 5000);
        assertNotNull(received, "expected a message routed to the AI queue");
        assertInstanceOf(TransactionSuccessEvent.class, received);
        assertEquals(payload.getTransactionId(), ((TransactionSuccessEvent) received).getTransactionId());

        // The event is now PUBLISHED (and processed) so it won't be relayed again.
        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED, reloaded.getStatus());
        assertTrue(reloaded.isProcessed());
        assertNotNull(reloaded.getPublishedAt());

        // A second relay is a no-op: the event is no longer eligible.
        assertEquals(0, relayService.relayBatch(), "published events must not be relayed twice");
    }

    @Test
    void dlqTopologyIsDeclared() {
        rabbitAdmin.initialize();

        // Consumer-side DLQ and publish-failure DLQ both exist on the broker.
        QueueInformation consumerDlq = rabbitAdmin.getQueueInfo(RabbitMQConfig.AI_QUEUE_DLQ);
        assertNotNull(consumerDlq, "consumer dead-letter queue should be declared");

        QueueInformation publishDlq = rabbitAdmin.getQueueInfo(RabbitMQConfig.OUTBOX_DLQ);
        assertNotNull(publishDlq, "publish-failure dead-letter queue should be declared");
    }
}
