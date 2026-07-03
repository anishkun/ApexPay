package com.example.ApexPay.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
    // Upper bound guards against absurd/overflow amounts; scale matches the account
    // balance column (precision 19, scale 4).
    @DecimalMax(value = "1000000000000.0000", message = "Amount exceeds the maximum allowed")
    @Digits(integer = 13, fraction = 4, message = "Amount has too many digits (max 4 decimal places)")
    private BigDecimal amount;
}
