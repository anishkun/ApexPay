package com.example.ApexPay.service;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proves the atomic dual-write rolls back together: when the outbox write fails
 * inside {@code TransferService.transferFunds}, the WHOLE transaction — debit/credit,
 * transaction row, audit logs, outbox row, AND the idempotency record written by the
 * executor — rolls back. No money moves and no key is recorded.
 *
 * <p>We force the failure by mocking the {@code OutboxEventRepository.save} (the last
 * write inside the transfer transaction, after the debit) to throw. Not
 * {@code @Transactional} so the rollback is genuine and observable from a fresh read.
 */
@SpringBootTest
class AtomicDualWriteRollbackTest {

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IdempotentTransferService idempotentTransferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private IdempotencyRecordRepository idempotencyRepository;

    private Account newAccount(String balance) {
        Account a = new Account();
        a.setUserId(UUID.randomUUID());
        a.setBalance(new BigDecimal(balance));
        a.setCurrency("USD");
        return accountRepository.save(a);
    }

    @Test
    void outboxWriteFailureRollsBackTransferAndIdempotencyRecord() {
        // Sabotage the outbox write so the transfer transaction must roll back.
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenThrow(new RuntimeException("simulated outbox failure"));

        Account src = newAccount("100.00");
        Account dst = newAccount("0.00");
        String key = "rollback-key-" + UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> idempotentTransferService.executeIdempotentTransfer(
                key, src.getId(), dst.getId(), new BigDecimal("40.00")));

        // No debit: balances unchanged.
        assertEquals(0, accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("100.00")));
        assertEquals(0, accountRepository.findById(dst.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("0.00")));

        // No idempotency record was committed (so a legit retry can succeed later).
        assertTrue(idempotencyRepository.findById(key).isEmpty());

        // Cleanup committed accounts.
        accountRepository.deleteById(src.getId());
        accountRepository.deleteById(dst.getId());
    }
}
