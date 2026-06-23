package com.example.ApexPay.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "apexpay.events";
    public static final String AI_QUEUE = "apexpay.ai.agent.queue";
    public static final String ROUTING_KEY_SUCCESS = "transaction.success.#";

    // --- Consumer-side dead-letter topology (poison messages from the AI queue) ---
    public static final String DLX = "apexpay.events.dlx";
    public static final String AI_QUEUE_DLQ = "apexpay.ai.agent.queue.dlq";
    public static final String DLQ_ROUTING_KEY = "ai.agent.dead";

    // --- Publish-failure dead-letter topology (DEAD outbox events the relay can't publish) ---
    public static final String OUTBOX_DLX = "apexpay.outbox.dlx";
    public static final String OUTBOX_DLQ = "apexpay.outbox.dlq";
    public static final String OUTBOX_DLQ_ROUTING_KEY = "outbox.publish.failed";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * Main AI queue, wired with dead-letter args so a rejected/exhausted message
     * is routed to the consumer DLX rather than dropped or infinitely requeued.
     */
    @Bean
    public Queue aiQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX);
        args.put("x-dead-letter-routing-key", DLQ_ROUTING_KEY);
        return new Queue(AI_QUEUE, true, false, false, args); // durable
    }

    @Bean
    public Binding binding(Queue aiQueue, TopicExchange exchange) {
        return BindingBuilder.bind(aiQueue).to(exchange).with(ROUTING_KEY_SUCCESS);
    }

    // --- Consumer-side DLX/DLQ ---

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Queue aiQueueDlq() {
        return new Queue(AI_QUEUE_DLQ, true);
    }

    @Bean
    public Binding deadLetterBinding(Queue aiQueueDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(aiQueueDlq).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    // --- Publish-failure DLX/DLQ (relay routes DEAD outbox events here) ---

    @Bean
    public DirectExchange outboxDeadLetterExchange() {
        return new DirectExchange(OUTBOX_DLX);
    }

    @Bean
    public Queue outboxDlq() {
        return new Queue(OUTBOX_DLQ, true);
    }

    @Bean
    public Binding outboxDeadLetterBinding(Queue outboxDlq, DirectExchange outboxDeadLetterExchange) {
        return BindingBuilder.bind(outboxDlq).to(outboxDeadLetterExchange).with(OUTBOX_DLQ_ROUTING_KEY);
    }

    // Forces RabbitMQ to send messages as JSON strings instead of byte arrays
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        // Publisher confirms are enabled globally via
        // spring.rabbitmq.publisher-confirm-type=correlated; the relay uses
        // RabbitTemplate.invoke(...).waitForConfirms(...) to block on the broker ACK.
        return template;
    }

    /**
     * Listener container factory with BOUNDED retry + exponential backoff. After
     * retries are exhausted the message is rejected (defaultRequeueRejected=false)
     * so it is dead-lettered to the consumer DLQ instead of requeued forever.
     */
    @Bean
    public RabbitListenerContainerFactory<SimpleMessageListenerContainer> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setDefaultRequeueRejected(false); // exhausted -> dead-letter, not requeue
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    /** Stateless retry: bounded attempts with exponential backoff between redeliveries. */
    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000) // initial 1s, multiplier 2x, max 10s
                .recoverer(new org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer())
                .build();
    }
}
