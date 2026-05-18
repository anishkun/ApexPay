package com.example.ApexPay.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionSuccessEvent {
    private UUID transactionId;
    private UUID sourceId;
    private UUID destinationId;
    private BigDecimal amount;
}