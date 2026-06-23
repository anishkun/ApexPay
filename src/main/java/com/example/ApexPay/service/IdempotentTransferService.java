package com.example.ApexPay.service;

import com.example.ApexPay.entity.IdempotencyRecord;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency facade for fund transfers — race-safe under concurrency.
 *
 * <p>This bean is deliberately NON-transactional. It orchestrates two distinct
 * transactional units of work (provided by {@link IdempotentTransferExecutor}, a
 * separate bean so Spring's proxy boundary actually applies):
 * <ol>
 *   <li>the happy-path "do the transfer AND record the key" transaction, and</li>
 *   <li>a separate read-only "look up the winner's result" transaction.</li>
 * </ol>
 *
 * <h2>Concurrent duplicate-key safety</h2>
 * The {@code IdempotencyRecord} INSERT shares the transfer's transaction and is
 * {@code saveAndFlush}-ed, so the unique-key (PK) constraint is enforced WITHIN that
 * transaction. When two requests with the same key race past the initial existence
 * check, exactly one wins the INSERT; the other's flush throws
 * {@link DataIntegrityViolationException}, which rolls back its ENTIRE transfer
 * (no double debit). The loser then re-reads the winner's committed record in a
 * fresh transaction and returns the winner's transaction. Result: exactly one debit,
 * both callers receive one consistent receipt.
 *
 * <h2>Optimistic-lock handling</h2>
 * The primary concurrency control on balances is the PESSIMISTIC_WRITE lock inside
 * {@link TransferService} (it serializes concurrent transfers on the same account).
 * The {@code @Version} column on {@code Account} is the secondary safety net. Should
 * an {@link OptimisticLockingFailureException} ever escape (e.g. an update path not
 * covered by the pessimistic lock), we transparently retry the transfer a bounded
 * number of times rather than surfacing a raw 500. If retries are exhausted the
 * exception is allowed to propagate and is translated to a 409 Conflict by
 * {@code GlobalExceptionHandler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTransferService {

    /** Bounded retries when an optimistic-lock conflict escapes the pessimistic lock. */
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    private final IdempotencyRecordRepository idempotencyRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotentTransferExecutor executor;

    public Transaction executeIdempotentTransfer(String idempotencyKey, UUID source, UUID dest, BigDecimal amount) {

        // 1. Fast path: a prior completed request already cached a result for this key.
        Optional<Transaction> replay = findCachedTransaction(idempotencyKey);
        if (replay.isPresent()) {
            log.info("Idempotency hit! Returning cached transaction for key: {}", idempotencyKey);
            return replay.get();
        }

        // 2. New request: run the transfer + record the key in one transaction, with a
        //    bounded optimistic-lock retry as the secondary safety net.
        OptimisticLockingFailureException lastLockFailure = null;
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                return executor.runTransferAndRecord(idempotencyKey, source, dest, amount);

            } catch (DataIntegrityViolationException dup) {
                // 3. Lost the duplicate-key race: a concurrent request with the SAME key
                //    won the INSERT, so our transfer was rolled back (no double debit).
                //    Recover by returning the winner's committed transaction.
                log.info("Idempotency race detected for key {}; returning winner's transaction", idempotencyKey);
                return executor.findRecordedTransaction(idempotencyKey);

            } catch (OptimisticLockingFailureException ole) {
                // 4. A @Version conflict escaped the pessimistic lock — retry the whole
                //    unit of work. The rolled-back attempt left no idempotency record.
                lastLockFailure = ole;
                log.warn("Optimistic-lock conflict on key {} (attempt {}/{}), retrying",
                        idempotencyKey, attempt, MAX_OPTIMISTIC_LOCK_RETRIES);
            }
        }
        // Retries exhausted: surface as a coherent conflict (409), not a raw 500.
        throw lastLockFailure;
    }

    /**
     * Read-only check for an already-recorded result. Kept on this non-transactional
     * facade (a single autocommit read) so the existence check stays cheap; the
     * race-safe guarantee comes from the in-transaction flush, not from this check.
     */
    private Optional<Transaction> findCachedTransaction(String idempotencyKey) {
        return idempotencyRepository.findById(idempotencyKey)
                .map(IdempotencyRecord::getTransactionId)
                .map(txId -> transactionRepository.findById(txId)
                        .orElseThrow(() -> new IllegalStateException("Cached transaction missing from DB")));
    }
}
