package com.example.ApexPay.entity;

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

    @Column(nullable = false)
    private boolean processed; // false = pending, true = sent to RabbitMQ

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}