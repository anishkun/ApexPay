package com.example.ApexPay.service;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.event.TransactionSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j // This gives us the 'log' variable to print to the console
@Component
@RequiredArgsConstructor
public class TransactionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    // This ONLY fires if the DB transaction commits successfully!
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTransactionSuccess(TransactionSuccessEvent event) {

        // Dynamic routing: High value transfers get a different key than standard ones
        String routingKey = event.getAmount().intValue() > 10000
                ? "transaction.success.high_value"
                : "transaction.success.standard";

        // AFTER_COMMIT listeners run synchronously on the committing (request) thread,
        // so the correlation ID placed in MDC by CorrelationIdFilter is still present.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, event, message -> {
            if (correlationId != null) {
                message.getMessageProperties().setHeader(CorrelationIdFilter.AMQP_HEADER, correlationId);
                message.getMessageProperties().setCorrelationId(correlationId);
            }
            return message;
        });
        log.info("Published to RabbitMQ: {} with routing key: {}", event.getTransactionId(), routingKey);
    }
}