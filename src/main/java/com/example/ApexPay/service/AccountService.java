package com.example.ApexPay.service;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.entity.AuditLog;
import com.example.ApexPay.enums.AuditAction;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * Opens a new account and records an immutable CREATED audit entry in the
     * same transaction, so an account never exists without its audit trail.
     */
    @Transactional
    public Account openAccount(UUID userId, BigDecimal initialBalance, String currency) {
        Account account = new Account();
        account.setUserId(userId);
        account.setBalance(initialBalance);
        account.setCurrency(currency);
        Account saved = accountRepository.save(account);

        AuditLog log = AuditLog.builder()
                .entityId(saved.getId())
                .action(AuditAction.CREATED)
                .previousState(null)
                .newState("{\"balance\":\"" + saved.getBalance() + "\",\"currency\":\"" + saved.getCurrency() + "\"}")
                .build();
        auditLogRepository.save(log);

        return saved;
    }
}
