package com.example.ApexPay.controller;

import com.example.ApexPay.dto.TransferRequest;
import com.example.ApexPay.entity.Transaction;
import com.example.ApexPay.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<Transaction> executeTransfer(@Valid @RequestBody TransferRequest request) {
        Transaction transaction = transferService.transferFunds(
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getAmount()
        );
        return ResponseEntity.ok(transaction);
    }
}