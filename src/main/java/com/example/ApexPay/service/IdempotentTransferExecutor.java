package com.example.ApexPay.service;

import com.example.ApexPay.entity.IdempotencyRecord;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Holds the transactional units of work for idempotent transfers.
 *
 * <p>This is a SEPARATE bean from {@link IdempotentTransferService} on purpose.
 * Spring's {@code @Transactional} is implemented with a proxy, so a transactional
 * method only opens a transaction when it is invoked THROUGH the proxy — i.e. from
 * a different bean. Calling a {@code @Transactional} method via {@code this.} inside
 * the same bean bypasses the proxy and the annotation is silently ignored. By moving
 * {@link #runTransferAndRecord} and {@link #findRecordedTransaction} here, the
 * facade's calls cross the proxy boundary and the transaction semantics actually apply.
 */
@Service
@RequiredArgsConstructor
public class IdempotentTransferExecutor {

    private final IdempotencyRecordRepository idempotencyRepository;
    private final TransactionRepository transactionRepository;
    private final TransferService coreTransferService;

    /**
     * Runs the transfer AND records the idempotency key in ONE transaction.
     *
     * <p>Atomicity: the debit/credit + outbox row (inside {@code transferFunds})
     * and the {@code IdempotencyRecord} insert share this single transaction. We
     * {@code saveAndFlush} the record so the unique-PK constraint is checked
     * <em>within</em> this transaction. If a concurrent request already inserted the
     * same key, the flush throws {@code DataIntegrityViolationException} and the
     * WHOLE transaction — including the debit — rolls back. Net effect: a losing
     * racer never double-debits; it recovers the winner's result via
     * {@link #findRecordedTransaction}.
     *
     * <p>Default {@code REQUIRED} propagation: because the facade
     * ({@code IdempotentTransferService}) is non-transactional, in production each
     * call opens its OWN transaction here, so a duplicate-key flush rolls back ONLY
     * this transfer and the loser can then recover the winner's result in a separate
     * transaction. Calling through this separate bean is what makes the proxy (and
     * thus the transaction + rollback) actually apply — a {@code this.}-style call
     * from the same bean would silently bypass it.
     */
    @Transactional
    public Transaction runTransferAndRecord(String idempotencyKey, UUID source, UUID dest, BigDecimal amount) {
        Transaction newTransaction = coreTransferService.transferFunds(source, dest, amount);

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setTransactionId(newTransaction.getId());
        // saveAndFlush forces the INSERT (and thus the unique-key constraint check)
        // to surface NOW, inside this transaction, instead of at commit time. A
        // duplicate key throws DataIntegrityViolationException and rolls everything
        // back — debit included.
        idempotencyRepository.saveAndFlush(record);

        return newTransaction;
    }

    /**
     * Re-loads the winner's recorded transaction in a fresh read-only transaction.
     *
     * <p>Used by the loser of an idempotency-key race after its own transfer rolled
     * back: the winner has already committed both the {@code IdempotencyRecord} and
     * the {@code Transaction}, so we look them up and return the winner's receipt.
     * A fresh read-only transaction (the facade is non-transactional, so this is a
     * new transaction that sees the winner's committed rows).
     */
    @Transactional(readOnly = true)
    public Transaction findRecordedTransaction(String idempotencyKey) {
        IdempotencyRecord record = idempotencyRepository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key " + idempotencyKey + " vanished after a constraint violation"));
        return transactionRepository.findById(record.getTransactionId())
                .orElseThrow(() -> new IllegalStateException("Cached transaction missing from DB"));
    }
}
