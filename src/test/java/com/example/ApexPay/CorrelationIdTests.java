package com.example.ApexPay;

import com.example.ApexPay.config.CorrelationIdFilter;
import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.example.ApexPay.service.RabbitMQConsumer;
import com.example.ApexPay.worker.OutboxRelayWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Verifies the correlation ID follows a payment end-to-end while staying broker-free:
 * HTTP header -> MDC (CorrelationIdFilter) -> OutboxEvent row -> relay worker ->
 * AMQP message header on the (mocked) RabbitTemplate -> consumer @Header parameter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIdTests {

	@TestConfiguration
	static class MockRabbitTemplateConfig {

		@Bean
		@Primary
		RabbitTemplate mockRabbitTemplate() {
			return Mockito.mock(RabbitTemplate.class);
		}
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private OutboxRelayWorker outboxRelayWorker;

	@Autowired
	private RabbitMQConsumer rabbitMQConsumer;

	@Autowired
	private RabbitTemplate rabbitTemplate; // resolves to the @Primary mock

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void providedCorrelationIdIsEchoedOnResponse() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(CorrelationIdFilter.HTTP_HEADER, "test-correlation-echo");

		ResponseEntity<String> response = restTemplate.exchange(
				"/actuator/health", org.springframework.http.HttpMethod.GET,
				new HttpEntity<>(headers), String.class);

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
	void correlationIdIsEchoedEvenOnUnauthorizedResponse() {
		// The CorrelationIdFilter (HIGHEST_PRECEDENCE servlet filter) runs before the
		// Spring Security chain, so even a rejected (401) request carries the ID back --
		// crucial for correlating auth failures in the logs.
		HttpHeaders headers = new HttpHeaders();
		headers.set(CorrelationIdFilter.HTTP_HEADER, "unauth-correlation");

		ResponseEntity<String> response = restTemplate.exchange(
				"/api/v1/transfers", org.springframework.http.HttpMethod.POST,
				new HttpEntity<>("{}", headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HTTP_HEADER))
				.isEqualTo("unauth-correlation");
	}

	@Test
	void correlationIdFlowsFromHttpRequestThroughOutboxToAmqpHeader() throws Exception {
		// Arrange: two funded accounts
		Account source = newAccount("1000.00");
		Account destination = newAccount("50.00");
		String correlationId = "e2e-" + UUID.randomUUID();

		// Act 1: HTTP POST with an explicit X-Correlation-Id
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		headers.set(CorrelationIdFilter.HTTP_HEADER, correlationId);
		String body = """
				{"sourceAccountId":"%s","destinationAccountId":"%s","amount":25.00}
				""".formatted(source.getId(), destination.getId());

		ResponseEntity<String> response = restTemplate
				.withBasicAuth("apiuser", "changeit")
				.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HTTP_HEADER)).isEqualTo(correlationId);

		JsonNode transaction = objectMapper.readTree(response.getBody());
		UUID transactionId = UUID.fromString(transaction.get("id").asText());

		// Assert: the outbox row persisted the correlation ID (the only carrier across
		// the async hop to the scheduler-thread relay worker)
		OutboxEvent outboxEvent = outboxEventRepository.findAll().stream()
				.filter(e -> transactionId.equals(e.getAggregateId()))
				.findFirst()
				.orElseThrow();
		assertThat(outboxEvent.getCorrelationId()).isEqualTo(correlationId);

		// Act 2: run the relay worker poll and capture what was sent to RabbitMQ
		outboxRelayWorker.processOutboxEvents();

		ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
		ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
		verify(rabbitTemplate, atLeastOnce())
				.convertAndSend(anyString(), anyString(), payloadCaptor.capture(), mppCaptor.capture());

		List<Object> payloads = payloadCaptor.getAllValues();
		int index = -1;
		for (int i = 0; i < payloads.size(); i++) {
			if (payloads.get(i) instanceof TransactionSuccessEvent tse
					&& transactionId.equals(tse.getTransactionId())) {
				index = i;
			}
		}
		assertThat(index).as("our transaction's event was relayed").isGreaterThanOrEqualTo(0);

		// Assert: applying the captured post-processor stamps the AMQP headers
		Message message = mppCaptor.getAllValues().get(index)
				.postProcessMessage(new Message(new byte[0], new MessageProperties()));
		assertThat(message.getMessageProperties().getHeader(CorrelationIdFilter.AMQP_HEADER).toString())
				.isEqualTo(correlationId);
		assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(correlationId);

		assertThat(outboxEventRepository.findById(outboxEvent.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isTrue());
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

	private Account newAccount(String balance) {
		Account account = new Account();
		account.setUserId(UUID.randomUUID());
		account.setBalance(new BigDecimal(balance));
		account.setCurrency("USD");
		return accountRepository.save(account);
	}
}
