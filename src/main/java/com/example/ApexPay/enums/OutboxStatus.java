package com.example.ApexPay.enums;

/**
 * Lifecycle of a transactional-outbox event as it is relayed to RabbitMQ.
 *
 * <ul>
 *   <li>{@link #PENDING}   - not yet (successfully) published; eligible for relay.</li>
 *   <li>{@link #PUBLISHED} - the broker confirmed (publisher confirm) it has the message.</li>
 *   <li>{@link #FAILED}    - the last publish attempt failed; retryable until attempts are exhausted
 *                            (still polled, gated by {@code nextAttemptAt}).</li>
 *   <li>{@link #DEAD}      - retries exhausted; routed to the publish-failure DLQ and no longer relayed.</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING, PUBLISHED, FAILED, DEAD
}
