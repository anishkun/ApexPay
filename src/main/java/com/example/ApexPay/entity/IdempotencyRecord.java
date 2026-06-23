package com.example.ApexPay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord implements Persistable<String> {

    // By making this the @Id, PostgreSQL guarantees it must be unique!
    @Id
    @Column(name = "idempotency_key", updatable = false, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public String getId() {
        return idempotencyKey;
    }

    /**
     * CRITICAL for the concurrent-idempotency guarantee.
     *
     * <p>This entity has an <b>application-assigned</b> {@code @Id} (the key) and no
     * {@code @Version}. Spring Data JPA's default {@code save()} therefore treats a
     * non-null id as "not new" and routes to {@code EntityManager.merge()}, which does
     * a SELECT-then-INSERT-or-UPDATE and does <b>not</b> reliably raise the unique-PK
     * {@code DataIntegrityViolationException} that the idempotency design relies on to
     * stop a duplicate-key racer. Under true concurrency that let multiple racers'
     * transfers commit (double debit).
     *
     * <p>By implementing {@link Persistable} and always reporting {@code isNew() == true},
     * {@code saveAndFlush} routes to {@code EntityManager.persist()} — a real INSERT —
     * so a duplicate key collides on the unique constraint and throws, rolling back the
     * losing racer's whole transfer. Records are insert-only (never updated), so always
     * returning {@code true} is correct.
     */
    @Override
    public boolean isNew() {
        return true;
    }
}
