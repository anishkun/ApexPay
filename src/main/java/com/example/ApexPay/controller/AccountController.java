package com.example.ApexPay.controller;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.exception.AccountNotFoundException;
import com.example.ApexPay.repository.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
        @NotNull(message = "User ID is required")
        private UUID userId;

        @NotNull(message = "Initial balance is required")
        @DecimalMin(value = "0.0", message = "Initial balance cannot be negative")
        @Digits(integer = 15, fraction = 4, message = "Initial balance has too many digits (max 4 decimal places)")
        private BigDecimal initialBalance;

        // ISO 4217 currency code: exactly three uppercase letters (e.g. USD, EUR).
        @NotNull(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code (e.g. USD)")
        private String currency;
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