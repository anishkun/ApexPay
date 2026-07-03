package com.example.ApexPay;

import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.enums.OutboxStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.repository.OutboxEventRepository;
import com.example.ApexPay.service.RabbitMQConsumer;
import com.example.ApexPay.worker.OutboxRelayWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies the Prometheus registry is live and that the custom apexpay.* meters
 * on the outbox relay (worker + service) and the RabbitMQ consumer behave as expected.
 * Broker-free: a @Primary mock RabbitTemplate shadows the real amqpTemplate bean; the
 * relay's publish path goes through {@code RabbitTemplate.invoke(...)}, which the mock
 * stubs to simulate a broker ACK (true) or a NACK/timeout (false).
 * The relay is enabled here (it is off in the default test profile) so the worker bean
 * and its poll timer exist; @AutoConfigureObservability re-enables metrics export so the
 * /actuator/prometheus endpoint is actually mounted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = "apexpay.outbox.relay.enabled=true")
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

	@AfterEach
	void resetBroker() {
		// Drop any per-test stubbing so a leftover event polled by the scheduler
		// in a later context does not behave unexpectedly.
		Mockito.reset(rabbitTemplate);
	}

	private double counterValue(String name) {
		return meterRegistry.get(name).counter().count();
	}

	private OutboxEvent.OutboxEventBuilder eventBuilder() throws Exception {
		TransactionSuccessEvent payload = new TransactionSuccessEvent(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("42.00"));
		return OutboxEvent.builder()
				.aggregateType("Transaction")
				.aggregateId(payload.getTransactionId())
				.eventType("TransactionSuccessEvent")
				.payload(objectMapper.writeValueAsString(payload))
				.processed(false);
	}

	/** A fresh PENDING event, immediately eligible for the relay. */
	private OutboxEvent seedPendingEvent() throws Exception {
		return outboxEventRepository.save(eventBuilder().build());
	}

	/** An event that already failed once and is eligible again — relaying it counts as a retry. */
	private OutboxEvent seedRetryableEvent() throws Exception {
		return outboxEventRepository.save(eventBuilder()
				.status(OutboxStatus.FAILED)
				.attemptCount(1)
				.nextAttemptAt(LocalDateTime.now().minusSeconds(1))
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
		// /actuator/prometheus now requires the ops (ROLE_ACTUATOR) credential.
		ResponseEntity<String> response = restTemplate
				.withBasicAuth("ops", "changeit")
				.getForEntity("/actuator/prometheus", String.class);
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
		// Broker ACKs the publish.
		Mockito.when(rabbitTemplate.invoke(any())).thenReturn(Boolean.TRUE);

		double publishedBefore = counterValue("apexpay.outbox.published");
		long pollsBefore = meterRegistry.get("apexpay.outbox.poll").timer().count();

		OutboxEvent event = seedPendingEvent();
		outboxRelayWorker.processOutboxEvents();

		assertThat(counterValue("apexpay.outbox.published")).isGreaterThanOrEqualTo(publishedBefore + 1);
		assertThat(meterRegistry.get("apexpay.outbox.poll").timer().count()).isGreaterThan(pollsBefore);
		assertThat(outboxEventRepository.findById(event.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isTrue());
	}

	@Test
	void outboxPublishFailureIncrementsFailedCounter() throws Exception {
		// Broker does not confirm (NACK/timeout): the relay records a failure and keeps the event pending.
		Mockito.when(rabbitTemplate.invoke(any())).thenReturn(Boolean.FALSE);

		double failedBefore = counterValue("apexpay.outbox.publish.failed");

		OutboxEvent event = seedPendingEvent();
		outboxRelayWorker.processOutboxEvents();

		assertThat(counterValue("apexpay.outbox.publish.failed")).isGreaterThanOrEqualTo(failedBefore + 1);
		assertThat(outboxEventRepository.findById(event.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isFalse());
	}

	@Test
	void outboxReattemptIncrementsRetriedCounter() throws Exception {
		// Broker has recovered: the previously-failed event is re-attempted and now succeeds.
		Mockito.when(rabbitTemplate.invoke(any())).thenReturn(Boolean.TRUE);

		double retriedBefore = counterValue("apexpay.outbox.retried");
		double publishedBefore = counterValue("apexpay.outbox.published");

		OutboxEvent event = seedRetryableEvent();
		outboxRelayWorker.processOutboxEvents();

		assertThat(counterValue("apexpay.outbox.retried")).isGreaterThanOrEqualTo(retriedBefore + 1);
		assertThat(counterValue("apexpay.outbox.published")).isGreaterThanOrEqualTo(publishedBefore + 1);
		assertThat(outboxEventRepository.findById(event.getId()))
				.hasValueSatisfying(e -> assertThat(e.isProcessed()).isTrue());
	}

	@Test
	void consumerListenerIncrementsConsumedCounterAndRecordsTimer() {
		double consumedBefore = counterValue("apexpay.consumer.consumed");
		long processedBefore = meterRegistry.get("apexpay.consumer.processing").timer().count();

		rabbitMQConsumer.consumeMessageFromQueue(new TransactionSuccessEvent(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00")), null);

		assertThat(counterValue("apexpay.consumer.consumed")).isEqualTo(consumedBefore + 1);
		assertThat(meterRegistry.get("apexpay.consumer.processing").timer().count()).isGreaterThan(processedBefore);
	}
}
