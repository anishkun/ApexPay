package com.example.ApexPay.worker;

import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.event.TransactionSuccessEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the CONSUMER-side dead-letter routing on a REAL RabbitMQ broker: a
 * "poison" message that the listener keeps rejecting is, after the bounded
 * retry is exhausted, dead-lettered to {@link RabbitMQConfig#AI_QUEUE_DLQ} via
 * the real DLX — while a good message reaches the listener normally.
 *
 * <h2>Why a test queue (and why it still proves the real routing)</h2>
 * The production {@link com.example.ApexPay.service.RabbitMQConsumer} only logs
 * and never throws, so it could never trigger dead-lettering on demand. Rather
 * than make production code throw on a sentinel, this test declares a dedicated
 * queue wired with the <b>same</b> {@code x-dead-letter-exchange} /
 * {@code x-dead-letter-routing-key} arguments as the production {@code aiQueue}
 * ({@link RabbitMQConfig#DLX} / {@link RabbitMQConfig#DLQ_ROUTING_KEY}), and a
 * listener that uses the production {@code rabbitListenerContainerFactory}
 * (real bounded retry: 3 attempts + {@code RejectAndDontRequeueRecoverer},
 * {@code defaultRequeueRejected=false}). The message dead-letters to the SAME
 * real {@link RabbitMQConfig#AI_QUEUE_DLQ} the production queue uses, so the DLX
 * topology + retry-then-dead-letter behaviour exercised here is identical to
 * production; only the queue the poison enters differs (so we don't collide with
 * the always-succeeding production consumer on the shared AI queue).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(ConsumerDlqRoutingIntegrationTest.TestTopology.class)
class ConsumerDlqRoutingIntegrationTest {

    static final String POISON_TEST_QUEUE = "apexpay.test.poison.queue";
    static final String POISON_ROUTING_KEY = "transaction.success.poison";

    /** Marker amount that makes the test listener throw (simulates a poison message). */
    static final BigDecimal POISON_AMOUNT = new BigDecimal("66.66");

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
        // The poison listener must actually run in this test.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private PoisonListener poisonListener;

    @Test
    void poisonMessageIsDeadLetteredToConsumerDlqAfterRetriesExhausted() throws Exception {
        rabbitAdmin.initialize();
        // Make sure no leftover messages from a previous run linger.
        rabbitAdmin.purgeQueue(RabbitMQConfig.AI_QUEUE_DLQ, false);

        TransactionSuccessEvent poison = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), POISON_AMOUNT);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, POISON_ROUTING_KEY, poison);

        // The listener must be invoked the full bounded-retry count (3) before giving up.
        assertTrue(poisonListener.attemptsLatch.await(15, TimeUnit.SECONDS),
                "listener should be retried up to the bounded max-attempts before dead-lettering");
        assertEquals(3, poisonListener.attempts.get(),
                "the stateless retry interceptor should make exactly 3 attempts");

        // After retries are exhausted the message must arrive on the REAL consumer DLQ.
        Object deadLettered = rabbitTemplate.receiveAndConvert(RabbitMQConfig.AI_QUEUE_DLQ, 10000);
        assertNotNull(deadLettered, "exhausted poison message must be dead-lettered to the consumer DLQ");
        assertInstanceOf(TransactionSuccessEvent.class, deadLettered);
        assertEquals(poison.getTransactionId(),
                ((TransactionSuccessEvent) deadLettered).getTransactionId(),
                "the message on the DLQ must be the original poison message");
    }

    @Test
    void goodMessageReachesTheListenerAndIsNotDeadLettered() throws Exception {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RabbitMQConfig.AI_QUEUE_DLQ, false);

        TransactionSuccessEvent good = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.34"));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, POISON_ROUTING_KEY, good);

        assertTrue(poisonListener.goodLatch.await(15, TimeUnit.SECONDS),
                "a good message should be delivered to the listener");

        // A good message is acked, so nothing should hit the DLQ.
        Object deadLettered = rabbitTemplate.receiveAndConvert(RabbitMQConfig.AI_QUEUE_DLQ, 2000);
        assertNull(deadLettered, "a successfully-consumed message must NOT be dead-lettered");
    }

    /**
     * Test-only topology: a queue wired with the SAME dead-letter args as the
     * production AI queue, bound to the production exchange under a poison routing
     * key, plus the throwing listener bean.
     */
    @TestConfiguration
    static class TestTopology {

        @Bean
        Queue poisonTestQueue() {
            Map<String, Object> args = new HashMap<>();
            // Identical DLX wiring to the production aiQueue.
            args.put("x-dead-letter-exchange", RabbitMQConfig.DLX);
            args.put("x-dead-letter-routing-key", RabbitMQConfig.DLQ_ROUTING_KEY);
            return new Queue(POISON_TEST_QUEUE, true, false, false, args);
        }

        @Bean
        Binding poisonTestBinding(Queue poisonTestQueue, TopicExchange exchange) {
            return BindingBuilder.bind(poisonTestQueue).to(exchange).with(POISON_ROUTING_KEY);
        }

        @Bean
        PoisonListener poisonListener() {
            return new PoisonListener();
        }
    }

    /**
     * Throws on the sentinel poison amount so the bounded retry exhausts and the
     * message is dead-lettered; otherwise records a successful delivery.
     */
    @Component
    static class PoisonListener {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch attemptsLatch = new CountDownLatch(3); // matches retryInterceptor maxAttempts
        final CountDownLatch goodLatch = new CountDownLatch(1);

        @RabbitListener(queues = POISON_TEST_QUEUE)
        public void consume(TransactionSuccessEvent event) {
            if (POISON_AMOUNT.compareTo(event.getAmount()) == 0) {
                attempts.incrementAndGet();
                attemptsLatch.countDown();
                throw new IllegalStateException("poison message - rejecting to force dead-letter");
            }
            goodLatch.countDown();
        }
    }
}
