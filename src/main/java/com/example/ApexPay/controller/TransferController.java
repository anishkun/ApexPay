package com.example.ApexPay.controller;

import com.example.ApexPay.dto.TransferRequest;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.exception.TransactionNotFoundException;
import com.example.ApexPay.repository.TransactionRepository;
import com.example.ApexPay.service.IdempotentTransferService; // Notice we use the Facade now!
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    // Inject the Facade instead of the core TransferService
    private final IdempotentTransferService idempotentTransferService;
    private final TransactionRepository transactionRepository;

    @PostMapping
    public ResponseEntity<Transaction> executeTransfer(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey, // Mandate the header
            @Valid @RequestBody TransferRequest request) {

        // Route the request through the Facade
        Transaction transaction = idempotentTransferService.executeIdempotentTransfer(
                idempotencyKey,
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getAmount()
        );
        return ResponseEntity.ok(transaction);
    }

    // Query a single payment by its transaction id
    @GetMapping("/{id}")
    public ResponseEntity<Transaction> getTransfer(@PathVariable UUID id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction " + id + " not found"));
        return ResponseEntity.ok(transaction);
    }
}