package com.example.ApexPay.migration;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.TransactionRepository;
import com.example.ApexPay.service.IdempotentTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The REAL multi-threaded proof of the idempotency concurrency fix, on a REAL
 * Postgres (Testcontainers) — not the single-threaded simulation in
 * {@link com.example.ApexPay.service.IdempotencyConcurrencyTest}.
 *
 * <p>H2's locking/constraint semantics differ from Postgres, so these tests
 * deliberately extend {@link AbstractPostgresIntegrationTest} to hit the
 * production database engine. They self-skip without Docker.
 *
 * <p>Determinism: every worker thread blocks on a shared {@link CountDownLatch}
 * (the "starting gun") that is released only once all threads are parked, so the
 * requests genuinely collide on the database rather than running sequentially.
 * No {@code Thread.sleep} is used to coordinate the race.
 */
class IdempotencyConcurrencyPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IdempotentTransferService idempotentTransferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private IdempotencyRecordRepository idempotencyRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account newAccount(String balance) {
        Account a = new Account();
        a.setUserId(UUID.randomUUID());
        a.setBalance(new BigDecimal(balance));
        a.setCurrency("USD");
        return accountRepository.save(a);
    }

    private long countTransactionsBetween(UUID src, UUID dst) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE source_account_id = ? AND destination_account_id = ?",
                Long.class, src, dst);
        return n == null ? 0 : n;
    }

    /**
     * N threads fire the SAME idempotency key + same transfer concurrently. The
     * source must be debited EXACTLY ONCE, exactly one Transaction + one
     * IdempotencyRecord must exist for the key, and every caller must receive the
     * SAME transaction id.
     */
    @Test
    void sameKeyManyThreadsDebitsExactlyOnce() throws Exception {
        final int threads = 24;
        final BigDecimal amount = new BigDecimal("30.00");
        Account src = newAccount("100.00");
        Account dst = newAccount("0.00");
        final UUID srcId = src.getId();
        final UUID dstId = dst.getId();
        final String key = "same-key-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Transaction>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await(); // all threads released simultaneously -> real collision
                return idempotentTransferService.executeIdempotentTransfer(key, srcId, dstId, amount);
            });
        }

        try {
            List<Future<Transaction>> futures = new ArrayList<>();
            for (Callable<Transaction> t : tasks) {
                futures.add(pool.submit(t));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "all threads should reach the barrier");
            start.countDown(); // fire!

            Set<UUID> returnedTxIds = new HashSet<>();
            for (Future<Transaction> f : futures) {
                Transaction tx = f.get(60, TimeUnit.SECONDS);
                assertNotNull(tx, "every caller must get a (non-null) transaction");
                returnedTxIds.add(tx.getId());
            }

            // 1. All N callers returned the SAME transaction id.
            assertEquals(1, returnedTxIds.size(),
                    () -> "all callers must share one transaction id, got: " + returnedTxIds);

            // 2. Source debited EXACTLY ONCE (100 - 30 = 70).
            assertEquals(0, accountRepository.findById(srcId).orElseThrow()
                            .getBalance().compareTo(new BigDecimal("70.00")),
                    "source must be debited exactly once");

            // 3. Destination credited exactly once (0 + 30 = 30).
            assertEquals(0, accountRepository.findById(dstId).orElseThrow()
                            .getBalance().compareTo(new BigDecimal("30.00")),
                    "destination must be credited exactly once");

            // 4. Exactly one Transaction row for this transfer.
            assertEquals(1, countTransactionsBetween(srcId, dstId),
                    "exactly one transaction row must exist for the transfer");

            // 5. Exactly one IdempotencyRecord for the key, pointing at that tx.
            assertTrue(idempotencyRepository.findById(key).isPresent(),
                    "the idempotency record for the key must exist");
            assertEquals(returnedTxIds.iterator().next(),
                    idempotencyRepository.findById(key).orElseThrow().getTransactionId(),
                    "the recorded transaction id must match what callers received");
        } finally {
            pool.shutdownNow();
            // Commit-visible rows: clean up so the shared Postgres container stays tidy.
            idempotencyRepository.deleteById(key);
            accountRepository.deleteById(srcId);
            accountRepository.deleteById(dstId);
        }
    }

    /**
     * N threads, DISTINCT keys, same source account, concurrent transfers. Proves
     * the pessimistic account locks prevent lost updates: every transfer applies
     * (sum conserved across src+dst), no negative balance, exactly N transactions,
     * N idempotency records.
     */
    @Test
    void distinctKeysSameSourceConservesBalanceWithNoLostUpdates() throws Exception {
        final int threads = 20;
        final BigDecimal amount = new BigDecimal("5.00");
        final BigDecimal initial = new BigDecimal("100.00"); // exactly 20 * 5.00
        Account src = newAccount(initial.toPlainString());
        Account dst = newAccount("0.00");
        final UUID srcId = src.getId();
        final UUID dstId = dst.getId();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> keys = new ArrayList<>();

        List<Callable<Transaction>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "distinct-" + i + "-" + UUID.randomUUID();
            keys.add(key);
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return idempotentTransferService.executeIdempotentTransfer(key, srcId, dstId, amount);
            });
        }

        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try {
            List<Future<Transaction>> futures = new ArrayList<>();
            for (Callable<Transaction> t : tasks) {
                futures.add(pool.submit(t));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "all threads should reach the barrier");
            start.countDown();

            Set<UUID> txIds = new HashSet<>();
            for (Future<Transaction> f : futures) {
                try {
                    Transaction tx = f.get(60, TimeUnit.SECONDS);
                    txIds.add(tx.getId());
                } catch (Exception e) {
                    firstError.compareAndSet(null, e.getCause() != null ? e.getCause() : e);
                }
            }
            assertNull(firstError.get(),
                    () -> "no transfer should fail under the pessimistic lock: " + firstError.get());

            // Every transfer produced a distinct transaction (distinct keys -> no dedupe).
            assertEquals(threads, txIds.size(), "each distinct key must yield its own transaction");

            BigDecimal srcFinal = accountRepository.findById(srcId).orElseThrow().getBalance();
            BigDecimal dstFinal = accountRepository.findById(dstId).orElseThrow().getBalance();

            // No lost updates: every 5.00 debit landed (100 - 20*5 = 0).
            assertEquals(0, srcFinal.compareTo(new BigDecimal("0.00")),
                    "source must reflect ALL debits (no lost updates)");
            assertEquals(0, dstFinal.compareTo(new BigDecimal("100.00")),
                    "destination must reflect ALL credits");
            // Money conserved.
            assertEquals(0, srcFinal.add(dstFinal).compareTo(initial),
                    "sum of balances must be conserved");
            // Never negative.
            assertTrue(srcFinal.compareTo(BigDecimal.ZERO) >= 0, "source must never go negative");
        } finally {
            pool.shutdownNow();
            for (String k : keys) {
                idempotencyRepository.deleteById(k);
            }
            accountRepository.deleteById(srcId);
            accountRepository.deleteById(dstId);
        }
    }
}
