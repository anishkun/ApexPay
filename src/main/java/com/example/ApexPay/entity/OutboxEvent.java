package com.example.ApexPay.entity;

import com.example.ApexPay.enums.OutboxStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String aggregateType; // e.g., "Transaction"

    @Column(nullable = false)
    private UUID aggregateId; // The ID of the transaction

    @Column(nullable = false)
    private String eventType; // e.g., "TransactionCompletedEvent"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload; // The JSON representation of the event

    /**
     * Convenience flag kept in sync with {@code status == PUBLISHED}. Retained for
     * backward compatibility with existing queries/tests ({@code findPendingEvents}).
     * Always mutate via {@link #markPublished()} / {@link #markPending()} so the two
     * stay consistent.
     */
    @Column(nullable = false)
    private boolean processed; // false = pending, true = sent to RabbitMQ (== status PUBLISHED)

    /** Resilience: relay lifecycle status (drives eligibility for the relay poll). */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutboxStatus status = OutboxStatus.PENDING;

    /** Number of publish attempts made so far (incremented on each failure). */
    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** When the event becomes eligible to be relayed again (null = immediately). */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** Last publish error, for ops visibility on FAILED/DEAD rows. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** When the broker confirmed the message (status moved to PUBLISHED). */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Optimistic-lock guard: prevents two racing relays from double-publishing the same row. */
    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Mark the event successfully published (status + convenience flag + timestamp). */
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.processed = true;
        this.publishedAt = LocalDateTime.now();
        this.nextAttemptAt = null;
    }

    /** Record a (still-retryable) publish failure with the next eligible time. */
    public void markFailed(String error, LocalDateTime nextAttemptAt) {
        this.status = OutboxStatus.FAILED;
        this.processed = false;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }

    /** Mark the event permanently dead after exhausting retries (routed to publish-failure DLQ). */
    public void markDead(String error) {
        this.status = OutboxStatus.DEAD;
        this.processed = false;
        this.lastError = error;
        this.nextAttemptAt = null;
    }

    private void markPending() {
        this.status = OutboxStatus.PENDING;
        this.processed = false;
    }
}
