package com.example.ApexPay.service;

import com.example.ApexPay.entity.*;
import com.example.ApexPay.enums.AuditAction;
import com.example.ApexPay.enums.TransactionStatus;
import com.example.ApexPay.event.TransactionSuccessEvent;
import com.example.ApexPay.exception.AccountNotFoundException;
import com.example.ApexPay.exception.InsufficientFundsException;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.AuditLogRepository;
import com.example.ApexPay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.example.ApexPay.repository.OutboxEventRepository outboxEventRepository; // ADD THIS
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Atomic dual-write guarantee.
     *
     * <p>Everything in this method runs inside a SINGLE {@code @Transactional}
     * boundary: the balance debit/credit, the {@code Transaction} record, the two
     * {@code AuditLog} rows, AND the {@code OutboxEvent} row all commit together or
     * roll back together. The outbox row is the ONLY "publish" side-effect we take
     * here — we deliberately do NOT touch RabbitMQ (or any broker) inside this
     * transaction. Broadcasting the event is the relay's job, which reads committed
     * outbox rows and publishes them AFTER this transaction commits
     * ({@code OutboxRelayService}). This keeps the business state and the
     * intent-to-publish perfectly consistent: if the commit fails, no event is ever
     * relayed; if it succeeds, the event is durably queued for at-least-once delivery.
     *
     * <p>Concurrency control here is PESSIMISTIC: both accounts are locked
     * {@code FOR UPDATE} (ordered by UUID to avoid deadlock), which serializes
     * concurrent transfers touching the same account. The {@code @Version} optimistic
     * lock on {@link com.example.ApexPay.entity.Account} is the secondary safety net
     * (see {@code IdempotentTransferService} for where the optimistic-lock failure is
     * caught and retried).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction transferFunds(UUID sourceId, UUID destinationId, BigDecimal amount) {

        // 1. Basic Validation
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive");
        }
        if (sourceId.equals(destinationId)) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account");
        }

        // 2. Deadlock Prevention: Determine locking order alphabetically
        UUID firstLockId = sourceId.compareTo(destinationId) < 0 ? sourceId : destinationId;
        UUID secondLockId = sourceId.compareTo(destinationId) < 0 ? destinationId : sourceId;

        // 3. Acquire Pessimistic Locks & capture entities immediately
        Account firstAccount = accountRepository.findByIdWithPessimisticLock(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account " + firstLockId + " not found"));
        Account secondAccount = accountRepository.findByIdWithPessimisticLock(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account " + secondLockId + " not found"));

        // 4. Safely assign entities back to source and destination
        Account source = sourceId.equals(firstLockId) ? firstAccount : secondAccount;
        Account destination = destinationId.equals(firstLockId) ? firstAccount : secondAccount;

        // 5. Verify Sufficient Funds
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in the source account");
        }

        // 6. Capture previous states for Audit Log
        String sourcePrevState = "{\"balance\":\"" + source.getBalance() + "\"}";
        String destPrevState = "{\"balance\":\"" + destination.getBalance() + "\"}";

        // 7. Execute Business Logic
        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destination.getBalance().add(amount));

        // 8. Save updated states
        accountRepository.save(source);
        accountRepository.save(destination);

        // 9. Record the Transaction
        Transaction transaction = new Transaction();
        transaction.setSourceAccountId(sourceId);
        transaction.setDestinationAccountId(destinationId);
        transaction.setAmount(amount);
        transaction.setStatus(TransactionStatus.COMPLETED);
        Transaction savedTransaction = transactionRepository.save(transaction);

        // 10. Write the Audit Logs
        createAuditLog(sourceId, AuditAction.DEBIT, sourcePrevState, "{\"balance\":\"" + source.getBalance() + "\"}");
        createAuditLog(destinationId, AuditAction.CREDIT, destPrevState, "{\"balance\":\"" + destination.getBalance() + "\"}");

        // 11. The Outbox Pattern: Save the event to the local DB
        try {
            TransactionSuccessEvent eventPayload = new TransactionSuccessEvent(
                    savedTransaction.getId(),
                    sourceId,
                    destinationId,
                    amount
            );

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("Transaction")
                    .aggregateId(savedTransaction.getId())
                    .eventType("TransactionSuccessEvent")
                    .payload(objectMapper.writeValueAsString(eventPayload)) // Convert to JSON
                    .processed(false)
                    .build();

            outboxEventRepository.save(outboxEvent);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // If we can't create the JSON, we intentionally crash to roll back the whole transaction
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }

        return savedTransaction;
    }

    private void createAuditLog(UUID entityId, AuditAction action, String prevState, String newState) {
        // Using the Builder pattern since setters are locked down
        AuditLog log = AuditLog.builder()
                .entityId(entityId)
                .action(action)
                .previousState(prevState)
                .newState(newState)
                .build();

        auditLogRepository.save(log);
    }
}
