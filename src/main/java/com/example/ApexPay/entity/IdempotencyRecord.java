package com.example.ApexPay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {

    // By making this the @Id, PostgreSQL guarantees it must be unique!
    @Id
    @Column(name = "idempotency_key", updatable = false, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}