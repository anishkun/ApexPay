package com.example.ApexPay.service;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class IdempotencyTest {

    @Autowired
    private IdempotentTransferService idempotentTransferService;
    @Autowired
    private AccountRepository accountRepository;

    private Account newAccount(String balance) {
        Account a = new Account();
        a.setUserId(UUID.randomUUID());
        a.setBalance(new BigDecimal(balance));
        a.setCurrency("USD");
        return accountRepository.save(a);
    }

    @Test
    void retryWithSameKeyReturnsCachedTransactionAndDeductsOnce() {
        Account src = newAccount("100.00");
        Account dst = newAccount("0.00");
        String key = "idem-key-" + UUID.randomUUID();

        Transaction first = idempotentTransferService.executeIdempotentTransfer(
                key, src.getId(), dst.getId(), new BigDecimal("30.00"));
        Transaction retry = idempotentTransferService.executeIdempotentTransfer(
                key, src.getId(), dst.getId(), new BigDecimal("30.00"));

        // Same receipt is returned, and funds were only moved once
        assertEquals(first.getId(), retry.getId());
        assertEquals(0, accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("70.00")));
        assertEquals(0, accountRepository.findById(dst.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("30.00")));
    }
}
