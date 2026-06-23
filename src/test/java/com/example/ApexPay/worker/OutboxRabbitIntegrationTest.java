package com.example.ApexPay.worker;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.entity.OutboxEvent;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the transactional-outbox → RabbitMQ path against a real
 * broker spun up in Docker. The whole class self-skips when no Docker daemon is
 * available (disabledWithoutDocker = true), so `mvn test` stays green offline.
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
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pendingOutboxEventIsDeliveredToQueue() throws Exception {
        // Declare exchange/queue/binding eagerly so the queue exists before we read.
        rabbitAdmin.initialize();

        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("250.00"));
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(objectMapper.writeValueAsString(payload))
                .processed(false)
                .build();
        outboxEventRepository.save(event);

        // Relay deterministically (the scheduled bean is disabled in tests).
        new OutboxRelayWorker(outboxEventRepository, rabbitTemplate, objectMapper).processOutboxEvents();

        Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.AI_QUEUE, 5000);
        assertNotNull(received, "expected a message routed to the AI queue");
        assertInstanceOf(TransactionSuccessEvent.class, received);
        assertEquals(payload.getTransactionId(), ((TransactionSuccessEvent) received).getTransactionId());

        // The event is now marked processed so it won't be relayed again.
        assertTrue(outboxEventRepository.findById(event.getId()).orElseThrow().isProcessed());
    }
}
