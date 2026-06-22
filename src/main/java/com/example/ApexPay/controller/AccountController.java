package com.example.ApexPay.controller;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.exception.AccountNotFoundException;
import com.example.ApexPay.repository.AccountRepository;
import com.example.ApexPay.service.AccountService;
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
    private final AccountService accountService;

    // DTO for creating an account
    @Data
    public static class CreateAccountRequest {
        @NotNull private UUID userId;
        @NotNull @DecimalMin("0.0") private BigDecimal initialBalance;
        @NotBlank private String currency;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.openAccount(
                request.getUserId(), request.getInitialBalance(), request.getCurrency());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccountBalance(@PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        return ResponseEntity.ok(account);
    }
}