package com.example.ApexPay.entity;

import com.example.ApexPay.enums.AuditAction; // Make sure this points to your enums package!
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter // Only getters, NO setters!
@Builder // Allows clean construction
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA, but hidden from devs
@AllArgsConstructor(access = AccessLevel.PRIVATE) // Required by @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditAction action;

    @Column(name = "previous_state", columnDefinition = "TEXT", updatable = false)
    private String previousState;

    @Column(name = "new_state", columnDefinition = "TEXT", updatable = false)
    private String newState;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;
}