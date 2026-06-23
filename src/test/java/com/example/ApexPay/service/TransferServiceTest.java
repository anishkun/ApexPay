package com.example.ApexPay.service;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.AuditLog;
import com.example.ApexPay.entity.OutboxEvent;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.enums.AuditAction;
import com.example.ApexPay.enums.TransactionStatus;
import com.example.ApexPay.exception.AccountNotFoundException;
import com.example.ApexPay.exception.InsufficientFundsException;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.AuditLogRepository;
import com.example.ApexPay.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional // each test runs in its own rolled-back transaction for isolation
class TransferServiceTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account newAccount(String balance) {
        Account a = new Account();
        a.setUserId(UUID.randomUUID());
        a.setBalance(new BigDecimal(balance));
        a.setCurrency("USD");
        return accountRepository.save(a);
    }

    @Test
    void transferMovesFundsRecordsTransactionAuditAndOutbox() {
        Account src = newAccount("100.00");
        Account dst = newAccount("0.00");

        Transaction tx = transferService.transferFunds(src.getId(), dst.getId(), new BigDecimal("40.00"));

        assertEquals(TransactionStatus.COMPLETED, tx.getStatus());
        assertEquals(0, accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("60.00")));
        assertEquals(0, accountRepository.findById(dst.getId()).orElseThrow()
                .getBalance().compareTo(new BigDecimal("40.00")));

        // Audit trail: a DEBIT against the source and a CREDIT against the destination
        List<AuditLog> srcAudit = auditLogRepository.findByEntityId(src.getId());
        List<AuditLog> dstAudit = auditLogRepository.findByEntityId(dst.getId());
        assertEquals(1, srcAudit.size());
        assertEquals(AuditAction.DEBIT, srcAudit.get(0).getAction());
        assertEquals(1, dstAudit.size());
        assertEquals(AuditAction.CREDIT, dstAudit.get(0).getAction());

        // Outbox: exactly one unprocessed event tied to this transaction
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents().stream()
                .filter(e -> e.getAggregateId().equals(tx.getId()))
                .toList();
        assertEquals(1, pending.size());
        assertFalse(pending.get(0).isProcessed());
        assertEquals("TransactionSuccessEvent", pending.get(0).getEventType());
    }

    @Test
    void insufficientFundsAreRejected() {
        Account src = newAccount("10.00");
        Account dst = newAccount("0.00");
        assertThrows(InsufficientFundsException.class,
                () -> transferService.transferFunds(src.getId(), dst.getId(), new BigDecimal("50.00")));
    }

    @Test
    void transferToSameAccountIsRejected() {
        Account a = newAccount("10.00");
        assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(a.getId(), a.getId(), BigDecimal.ONE));
    }

    @Test
    void unknownAccountIsRejected() {
        Account src = newAccount("10.00");
        assertThrows(AccountNotFoundException.class,
                () -> transferService.transferFunds(src.getId(), UUID.randomUUID(), BigDecimal.ONE));
    }
}
