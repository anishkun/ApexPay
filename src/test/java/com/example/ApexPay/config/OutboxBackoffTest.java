package com.example.ApexPay.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit test of the exponential-backoff formula:
 * {@code delay(attempt) = min(base * 2^(attempt-1), max)}.
 */
class OutboxBackoffTest {

    private OutboxProperties props(long base, long max) {
        OutboxProperties p = new OutboxProperties();
        p.setBackoffBaseMs(base);
        p.setBackoffMaxMs(max);
        return p;
    }

    @Test
    void doublesEachAttemptUntilCapped() {
        OutboxProperties p = props(1000, 60000);
        assertEquals(0, p.backoffMillis(0), "attempt < 1 yields no delay");
        assertEquals(1000, p.backoffMillis(1));   // 1000 * 2^0
        assertEquals(2000, p.backoffMillis(2));   // 1000 * 2^1
        assertEquals(4000, p.backoffMillis(3));   // 1000 * 2^2
        assertEquals(8000, p.backoffMillis(4));   // 1000 * 2^3
        assertEquals(16000, p.backoffMillis(5));  // 1000 * 2^4
        assertEquals(32000, p.backoffMillis(6));  // 1000 * 2^5
        // 1000 * 2^6 = 64000 > 60000 -> capped.
        assertEquals(60000, p.backoffMillis(7));
        assertEquals(60000, p.backoffMillis(8));
    }

    @Test
    void neverOverflowsForLargeAttemptCounts() {
        OutboxProperties p = props(1000, 60000);
        // A pathologically large attempt count must still clamp to the cap.
        assertEquals(60000, p.backoffMillis(100));
        assertEquals(60000, p.backoffMillis(1000));
    }

    @Test
    void respectsCustomBaseAndCap() {
        OutboxProperties p = props(250, 5000);
        assertEquals(250, p.backoffMillis(1));
        assertEquals(500, p.backoffMillis(2));
        assertEquals(1000, p.backoffMillis(3));
        assertEquals(2000, p.backoffMillis(4));
        assertEquals(4000, p.backoffMillis(5));
        assertEquals(5000, p.backoffMillis(6)); // 8000 capped to 5000
    }
}
