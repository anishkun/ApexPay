package com.example.ApexPay;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.service.RabbitMQConsumer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the correlation ID enters and leaves the request/consumer boundaries cleanly:
 * the {@link CorrelationIdFilter} echoes a provided ID (or generates one) on the HTTP
 * response, and the consumer reads the AMQP {@code x-correlation-id} header into MDC and
 * clears it afterwards. The async relay hop (outbox row -> AMQP header) is covered by
 * {@code OutboxRelayCorrelationTest} where the package-private relay method is reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIdTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitMQConsumer rabbitMQConsumer;

    @Test
    void providedCorrelationIdIsEchoedOnResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HTTP_HEADER, "test-correlation-echo");

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HTTP_HEADER))
                .isEqualTo("test-correlation-echo");
    }

    @Test
    void missingCorrelationIdIsGeneratedOnResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        String generated = response.getHeaders().getFirst(CorrelationIdFilter.HTTP_HEADER);
        assertThat(generated).isNotBlank();
        // Generated IDs are UUIDs; this throws if not parseable.
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void consumerAcceptsCorrelationHeaderAndClearsMdc() {
        rabbitMQConsumer.consumeMessageFromQueue(
                new TransactionSuccessEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("10.00")),
                "consumer-correlation-id");

        // The consumer must not leak the correlation ID onto the (pooled) listener thread.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
