package com.example.ApexPay.service;

import com.example.ApexPay.entity.IdempotencyRecord;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTransferService {

    private final IdempotencyRecordRepository idempotencyRepository;
    private final TransactionRepository transactionRepository;
    private final TransferService coreTransferService; // Your Phase 2 engine

    public Transaction executeIdempotentTransfer(String idempotencyKey, UUID source, UUID dest, BigDecimal amount) {

        // 1. Check if we have already processed this exact request
        Optional<IdempotencyRecord> existingRecord = idempotencyRepository.findById(idempotencyKey);

        if (existingRecord.isPresent()) {
            log.info("Idempotency hit! Returning cached transaction for key: {}", idempotencyKey);
            // Return the previously successful transaction
            return transactionRepository.findById(existingRecord.get().getTransactionId())
                    .orElseThrow(() -> new IllegalStateException("Cached transaction missing from DB"));
        }

        // 2. If it's a new request, execute the core engine
        Transaction newTransaction = coreTransferService.transferFunds(source, dest, amount);

        // 3. Save the key so future retries are caught
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setTransactionId(newTransaction.getId());
        idempotencyRepository.save(record);

        return newTransaction;
    }
}