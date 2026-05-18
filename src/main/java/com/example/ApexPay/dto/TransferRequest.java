package com.example.ApexPay.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferRequest {
    @NotNull(message = "Source Account ID is required")
    private UUID sourceAccountId;

    @NotNull(message = "Destination Account ID is required")
    private UUID destinationAccountId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;
}