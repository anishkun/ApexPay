package com.example.ApexPay.repository;

import com.example.ApexPay.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Back-compat: all events not yet published, oldest first. Still used by the
     * transfer-service test to assert a freshly-written event is pending.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.processed = false ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents();

    /**
     * Cross-DB relay poll: eligible events (PENDING or retryable FAILED) whose
     * backoff window has elapsed, oldest first, bounded by {@code pageable}.
     *
     * <p>Rows are pessimistically locked ({@code FOR UPDATE}) so two relay
     * instances don't both fetch the same row inside their transactions. The
     * {@code @Version} optimistic lock is the hard guarantee against
     * double-publish even if pessimistic locking degrades on a given dialect.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.status IN (com.example.ApexPay.enums.OutboxStatus.PENDING,
                               com.example.ApexPay.enums.OutboxStatus.FAILED)
              AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :now)
            ORDER BY o.createdAt ASC
            """)
    List<OutboxEvent> findEligibleForRelay(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Postgres-native relay poll using {@code FOR UPDATE SKIP LOCKED}: concurrent
     * relays each grab a disjoint batch instead of blocking on each other. Only
     * used on the Postgres path (guarded by config); H2 uses
     * {@link #findEligibleForRelay}.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status IN ('PENDING', 'FAILED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findEligibleForRelaySkipLocked(@Param("now") LocalDateTime now,
                                                     @Param("batchSize") int batchSize);
}
