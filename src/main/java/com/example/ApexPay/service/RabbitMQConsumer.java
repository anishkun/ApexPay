package com.example.ApexPay.service;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.config.RabbitMQConfig;
import com.example.ApexPay.event.TransactionSuccessEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RabbitMQConsumer {

    private final MeterRegistry meterRegistry;

    // Metrics (registered eagerly at construction so they appear in /actuator/prometheus at zero)
    private final Counter consumedCounter;
    private final Counter failedCounter;
    private final Timer processingTimer;

    public RabbitMQConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.consumedCounter = Counter.builder("apexpay.consumer.consumed")
                .description("Messages successfully processed by the RabbitMQ consumer")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("apexpay.consumer.failed")
                .description("Messages whose processing threw an exception in the RabbitMQ consumer")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("apexpay.consumer.processing")
                .description("Per-message processing duration in the RabbitMQ consumer")
                .register(meterRegistry);
    }

    // This annotation tells Spring to listen to this specific queue 24/7
    @RabbitListener(queues = RabbitMQConfig.AI_QUEUE)
    public void consumeMessageFromQueue(
            TransactionSuccessEvent event,
            @Header(name = CorrelationIdFilter.AMQP_HEADER, required = false) String correlationId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Rejoin the payment's correlation trail carried in the AMQP header.
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            log.info("Message received from AI Queue! Preparing for AI analysis: {}", event);
            consumedCounter.increment();
        } catch (RuntimeException e) {
            failedCounter.increment();
            throw e; // rethrow: listener retry/requeue semantics must stay unchanged
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            sample.stop(processingTimer);
        }
    }
}
