package com.example.ApexPay;

import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.example.ApexPay.service.RabbitMQConsumer;
import com.example.ApexPay.worker.OutboxRelayWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Verifies the Prometheus registry is live and that the custom apexpay.* meters
 * on the outbox relay worker and RabbitMQ consumer behave as expected.
 * Broker-free: a @Primary mock RabbitTemplate shadows the real amqpTemplate bean.
 * @AutoConfigureObservability re-enables metrics export (disabled by default in tests)
 * so the /actuator/prometheus endpoint is actually mounted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class MetricsTests {

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
	private MeterRegistry meterRegistry;

	@Autowired
	private OutboxRelayWorker outboxRelayWorker;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private RabbitMQConsumer rabbitMQConsumer;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private RabbitTemplate rabbitTemplate; // resolves to the @Primary mock

	private double counterValue(String name) {
		return meterRegistry.get(name).counter().count();
	}

	private OutboxEvent seedOutboxEvent() throws Exception {
		TransactionSuccessEvent payload = new TransactionSuccessEvent(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("42.00"));
		return outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType("Transaction")
				.aggregateId(payload.getTransactionId())
				.eventType("TransactionSuccessEvent")
				.payload(objectMapper.writeValueAsString(payload))
				.processed(false)
				.build());
	}

	@Test
	void allApexPayMetersAreRegisteredEagerly() {
		// Counters/timers must exist (at zero) before any increment, for alerting.
		assertThat(meterRegistry.find("apexpay.outbox.published").counter()).isNotNull();
		assertThat(meterRegistry.find("apexpay.outbox.publish.failed").counter()).isNotNull();
		assertThat(meterRegistry.find("apexpay.outbox.retried").counter()).isNotNull();
		assertThat(meterRegistry.find("apexpay.outbox.poll").timer()).isNotNull();
		assertThat(meterRegistry.find("apexpay.consumer.consumed").counter()).isNotNull();
		assertThat(meterRegistry.find("apexpay.consumer.failed").counter()).isNotNull();
		assertThat(meterRegistry.find("apexpay.consumer.processing").timer()).isNotNull();
	}

	@Test
	void prometheusEndpointExposesApexPayMeters() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody())
				.contains("apexpay_outbox_published_total")
				.contains("apexpay_outbox_publish_failed_total")
				.contains("apexpay_outbox_retried_total")
				.contains("apexpay_outbox_poll_seconds")
				.contains("apexpay_consumer_consumed_total")
				.contains("apexpay_consumer_failed_total")
				.contains("apexpay_consumer_processing_seconds");
	}

	@Test
	void outboxPublishSuccessIncrementsPublishedCounterAndRecordsPoll() throws Exception {
		double publishedBefore = counterValue("apexpay.outbox.published");
		long pollsBefore = meterRegistry.get("apexpay.outbox.poll").timer().count();

		OutboxEvent event = seedOutboxEvent();
		outboxRelayWorker.processOutboxEvents();

		assertThat(counterValue("apexpay.outbox.published")).isGreaterThanOrEqualTo(publishedBefore + 1);
		assertThat(meterRegistry.get("apexpay.outbox.poll").timer().count()).isGreaterThan(pollsBefore);
		assertThat(outboxEventRepository.findById(event.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isTrue());
	}

	@Test
	void outboxPublishFailureIncrementsFailedThenRetriedOnReattempt() throws Exception {
		double failedBefore = counterValue("apexpay.outbox.publish.failed");
		double retriedBefore = counterValue("apexpay.outbox.retried");

		doThrow(new AmqpException("broker unavailable"))
				.when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());

		OutboxEvent event = seedOutboxEvent();
		try {
			outboxRelayWorker.processOutboxEvents();

			assertThat(counterValue("apexpay.outbox.publish.failed")).isGreaterThanOrEqualTo(failedBefore + 1);
			assertThat(outboxEventRepository.findById(event.getId()))
					.hasValueSatisfying(e -> assertThat(e.isProcessed()).isFalse());
		} finally {
			// "Broker recovers" for the retry phase and for any other test in this context.
			Mockito.reset(rabbitTemplate);
		}

		// The re-poll of the previously failed row counts as a retry and now succeeds.
		outboxRelayWorker.processOutboxEvents();

		assertThat(counterValue("apexpay.outbox.retried")).isGreaterThanOrEqualTo(retriedBefore + 1);
		assertThat(outboxEventRepository.findById(event.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isTrue());
	}

	@Test
	void consumerListenerIncrementsConsumedCounterAndRecordsTimer() {
		double consumedBefore = counterValue("apexpay.consumer.consumed");
		long processedBefore = meterRegistry.get("apexpay.consumer.processing").timer().count();

		rabbitMQConsumer.consumeMessageFromQueue(new TransactionSuccessEvent(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00")));

		assertThat(counterValue("apexpay.consumer.consumed")).isEqualTo(consumedBefore + 1);
		assertThat(meterRegistry.get("apexpay.consumer.processing").timer().count()).isGreaterThan(processedBefore);
	}
}
