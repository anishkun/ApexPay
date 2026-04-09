package com.example.ApexPay.entity;



import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @NotNull
    @DecimalMin(value = "0.0", message = "Balance cannot be negative")
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @NotNull
    @Column(nullable = false, length = 3)
    private String currency; // e.g., USD, EUR

    @Version
    private Long version; // Enables optimistic locking
}