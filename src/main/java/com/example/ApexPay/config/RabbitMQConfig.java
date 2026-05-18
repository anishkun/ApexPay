package com.example.ApexPay.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "apexpay.events";
    public static final String AI_QUEUE = "apexpay.ai.agent.queue";
    public static final String ROUTING_KEY_SUCCESS = "transaction.success.#";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue aiQueue() {
        return new Queue(AI_QUEUE, true); // true = durable queue (survives restarts)
    }

    @Bean
    public Binding binding(Queue aiQueue, TopicExchange exchange) {
        return BindingBuilder.bind(aiQueue).to(exchange).with(ROUTING_KEY_SUCCESS);
    }

    // Forces RabbitMQ to send messages as JSON strings instead of byte arrays
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public AmqpTemplate amqpTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}