package com.example.ApexPay.controller;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.exception.AccountNotFoundException;
import com.example.ApexPay.repository.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;

    // DTO for creating an account
    @Data
    public static class CreateAccountRequest {
        @NotNull private UUID userId;
        @NotNull @DecimalMin("0.0") private BigDecimal initialBalance;
        @NotBlank private String currency;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = new Account();
        account.setUserId(request.getUserId());
        account.setBalance(request.getInitialBalance());
        account.setCurrency(request.getCurrency());
        // In a real app, you'd save an AuditLog for CREATED here as well

        return ResponseEntity.status(HttpStatus.CREATED).body(accountRepository.save(account));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccountBalance(@PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        return ResponseEntity.ok(account);
    }
}