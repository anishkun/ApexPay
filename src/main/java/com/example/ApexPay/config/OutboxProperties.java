package com.example.ApexPay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the resilient outbox relay, bound from {@code apexpay.outbox.*}.
 *
 * <p>Backoff is exponential: {@code delay(attempt) = min(backoffBaseMs * 2^(attempt-1), backoffMaxMs)}.
 */
@Component
@ConfigurationProperties(prefix = "apexpay.outbox")
public class OutboxProperties {

    /** Max publish attempts before an event is marked DEAD and routed to the publish-failure DLQ. */
    private int maxAttempts = 5;

    /** Base backoff delay in ms (the delay after the first failure). */
    private long backoffBaseMs = 1000;

    /** Cap on backoff delay in ms (exponential growth is clamped to this). */
    private long backoffMaxMs = 60000;

    /** Max events fetched per relay poll. */
    private int batchSize = 100;

    /** How long to wait (ms) for a broker publisher confirm before treating it as a failure. */
    private long confirmTimeoutMs = 5000;

    /** Use the Postgres-native FOR UPDATE SKIP LOCKED poll (true in prod/pg; false on H2). */
    private boolean useSkipLocked = false;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public long getBackoffMaxMs() {
        return backoffMaxMs;
    }

    public void setBackoffMaxMs(long backoffMaxMs) {
        this.backoffMaxMs = backoffMaxMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getConfirmTimeoutMs() {
        return confirmTimeoutMs;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public boolean isUseSkipLocked() {
        return useSkipLocked;
    }

    public void setUseSkipLocked(boolean useSkipLocked) {
        this.useSkipLocked = useSkipLocked;
    }

    /**
     * Exponential backoff: {@code min(base * 2^(attempt-1), max)}. {@code attempt}
     * is the attempt number that just failed (1-based). Returns 0 for attempt &lt; 1.
     */
    public long backoffMillis(int attempt) {
        if (attempt < 1) {
            return 0;
        }
        // Compute 2^(attempt-1) guarding against long overflow before clamping.
        int shift = attempt - 1;
        long factor = (shift >= 62) ? Long.MAX_VALUE : (1L << shift);
        long delay;
        if (factor > backoffMaxMs / Math.max(1, backoffBaseMs)) {
            delay = backoffMaxMs; // would overflow / exceed the cap
        } else {
            delay = backoffBaseMs * factor;
        }
        return Math.min(delay, backoffMaxMs);
    }
}
