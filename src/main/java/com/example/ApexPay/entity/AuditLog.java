package com.example.ApexPay.entity;


import com.example.ApexPay.enums.AuditAction;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Data
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "entity_id", nullable = false)
    private UUID entityId; // Could be Account ID or Transaction ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    // Storing state as JSON strings is a common practice for audit logs
    @Column(name = "previous_state", columnDefinition = "TEXT")
    private String previousState;

    @Column(name = "new_state", columnDefinition = "TEXT")
    private String newState;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;
}
