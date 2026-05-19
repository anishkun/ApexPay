package com.example.ApexPay.controller;

import com.example.ApexPay.dto.TransferRequest;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.service.IdempotentTransferService; // Notice we use the Facade now!
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    // Inject the Facade instead of the core TransferService
    private final IdempotentTransferService idempotentTransferService;

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
}