package com.example.ApexPay.service;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.IdempotencyRecord;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the concurrent duplicate-idempotency-key path is race-safe: exactly one
 * debit and both callers get the same transaction.
 *
 * <p>This test is intentionally NOT {@code @Transactional} — it must let the
 * executor's {@code REQUIRES_NEW} transactions actually commit and roll back so we
 * can observe the committed end state. To simulate the "winner already committed"
 * race deterministically (without a flaky multi-thread harness — that exhaustive
 * suite is the next milestone), we pre-insert the winner's {@code IdempotencyRecord}
 * for the key, then fire the transfer with the SAME key. The in-transaction flush
 * hits the PK constraint, rolls the transfer back (no debit), and the facade returns
 * the winner's recorded transaction.
 */
@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotentTransferService idempotentTransferService;
    @Autowired
    private TransferService transferService;
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
    void duplicateKeyRaceDebitsOnceAndBothCallersGetWinnersTransaction() {
        Account src = newAccount("100.00");
        Account dst = newAccount("0.00");
        String key = "race-key-" + UUID.randomUUID();

        // The "winner": a transfer that already committed and recorded the key.
        Transaction winner = transferService.transferFunds(src.getId(), dst.getId(), new BigDecimal("30.00"));
        IdempotencyRecord winnerRecord = new IdempotencyRecord();
        winnerRecord.setIdempotencyKey(key);
        winnerRecord.setTransactionId(winner.getId());
        idempotencyRepository.saveAndFlush(winnerRecord);

        BigDecimal srcAfterWinner = accountRepository.findById(src.getId()).orElseThrow().getBalance();

        // The "loser": same key, would debit again — but the duplicate-key INSERT must
        // roll its transfer back and instead return the winner's transaction.
        Transaction loser = idempotentTransferService.executeIdempotentTransfer(
                key, src.getId(), dst.getId(), new BigDecimal("30.00"));

        // Both callers see the same receipt.
        assertEquals(winner.getId(), loser.getId());

        // The loser did NOT debit: balance is unchanged from after the winner.
        assertEquals(0, accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(srcAfterWinner));
        // Exactly one 30.00 debit total (100 - 30 = 70).
        assertEquals(0, accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("70.00")));

        // Cleanup: this test commits, so remove the rows it created.
        accountRepository.deleteById(src.getId());
        accountRepository.deleteById(dst.getId());
        idempotencyRepository.deleteById(key);
    }
}
