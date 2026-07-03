package com.example.ApexPay.service;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.config.OutboxProperties;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broker-free proof that the correlation ID persisted on the outbox row survives the
 * async relay hop: {@link OutboxRelayService#relayOne} restores it to MDC while
 * publishing, stamps it on the AMQP message (custom header + standard correlationId),
 * and clears MDC afterwards so it never leaks onto the scheduler thread.
 */
class OutboxRelayCorrelationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void relayRestoresCorrelationIdToMdcAndStampsAmqpHeader() throws Exception {
        String correlationId = "e2e-" + UUID.randomUUID();
        OutboxEvent event = seededEvent(correlationId);

        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitOperations operations = mock(RabbitOperations.class);
        when(operations.waitForConfirms(anyLong())).thenReturn(true);

        // Execute the invoke callback against the mock RabbitOperations, capturing the MDC
        // value visible at publish time so we can assert the trail was restored.
        AtomicReference<String> mdcDuringPublish = new AtomicReference<>();
        when(rabbitTemplate.invoke(any())).thenAnswer(inv -> {
            mdcDuringPublish.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            RabbitOperations.OperationsCallback<?> callback = inv.getArgument(0);
            return callback.doInRabbit(operations);
        });

        OutboxRelayService relay = new OutboxRelayService(
                repository, rabbitTemplate, objectMapper, new OutboxProperties(), new SimpleMeterRegistry());

        boolean published = relay.relayOne(event);

        assertThat(published).isTrue();
        assertThat(event.isProcessed()).isTrue();
        assertThat(mdcDuringPublish.get())
                .as("relay restores the correlation ID to MDC while publishing")
                .isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                .as("relay clears MDC after publishing (no leak onto the scheduler thread)")
                .isNull();

        // The publish post-processor stamps both the custom header and the AMQP correlationId.
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(operations).convertAndSend(anyString(), anyString(), any(), mpp.capture(),
                any(CorrelationData.class));

        Message message = mpp.getValue()
                .postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().getHeader(CorrelationIdFilter.AMQP_HEADER).toString())
                .isEqualTo(correlationId);
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(correlationId);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.getId().toString());
    }

    private OutboxEvent seededEvent(String correlationId) throws Exception {
        TransactionSuccessEvent payload = new TransactionSuccessEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Transaction")
                .aggregateId(payload.getTransactionId())
                .eventType("TransactionSuccessEvent")
                .payload(objectMapper.writeValueAsString(payload))
                .processed(false)
                .correlationId(correlationId)
                .build();
    }
}
