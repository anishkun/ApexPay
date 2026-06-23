package com.example.ApexPay.worker;

import com.example.ApexPay.service.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for the resilient outbox relay. The actual publish/confirm/
 * retry/dead-letter logic lives in {@link OutboxRelayService}; this bean only
 * drives it on a fixed delay. Disabled via
 * {@code apexpay.outbox.relay.enabled=false} (e.g. in fast tests).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "apexpay.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayWorker {

    private final OutboxRelayService relayService;

    @Scheduled(fixedDelay = 5000)
    public void processOutboxEvents() {
        try {
            relayService.relayBatch();
        } catch (Exception e) {
            // Never let a relay cycle's failure kill the scheduler thread.
            log.error("Outbox relay cycle failed; will retry next tick. Error: {}", e.getMessage());
        }
    }
}
