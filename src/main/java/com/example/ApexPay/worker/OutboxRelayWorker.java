package com.example.ApexPay.worker;

import com.example.ApexPay.service.OutboxRelayService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Scheduled trigger for the resilient outbox relay. The actual publish/confirm/
 * retry/dead-letter logic lives in {@link OutboxRelayService}; this bean only
 * drives it on a fixed delay and times each poll. Disabled via
 * {@code apexpay.outbox.relay.enabled=false} (e.g. in fast tests).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "apexpay.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayWorker {

    private final OutboxRelayService relayService;

    // Times one full relay poll cycle. Registered eagerly at construction so it
    // appears in /actuator/prometheus at zero before the first poll runs.
    private final Timer pollTimer;

    public OutboxRelayWorker(OutboxRelayService relayService, MeterRegistry meterRegistry) {
        this.relayService = relayService;
        this.pollTimer = Timer.builder("apexpay.outbox.poll")
                .description("Duration of one outbox relay poll cycle")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 5000)
    public void processOutboxEvents() {
        long start = System.nanoTime();
        try {
            relayService.relayBatch();
        } catch (Exception e) {
            // Never let a relay cycle's failure kill the scheduler thread.
            log.error("Outbox relay cycle failed; will retry next tick. Error: {}", e.getMessage());
        } finally {
            pollTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
