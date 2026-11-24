package com.example.ApexPay.service;

import com.example.ApexPay.repository.IdempotencyRecordRepository;
import com.example.ApexPay.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentTransferServiceTest {

    /**
     * A constraint violation that is NOT a duplicate key leaves no record behind, so it
     * must surface as itself instead of "key vanished after a constraint violation".
     */
    @Test
    void integrityErrorThatIsNotADuplicateKeyIsRethrown() {
        IdempotencyRecordRepository records = mock(IdempotencyRecordRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        TransferService transferService = mock(TransferService.class);
        IdempotentTransferService service = new IdempotentTransferService(records, transactions,
                new IdempotentTransferExecutor(records, transactions, transferService));

        DataIntegrityViolationException original = new DataIntegrityViolationException("numeric field overflow");
        when(transferService.transferFunds(any(), any(), any())).thenThrow(original);

        assertSame(original, assertThrows(RuntimeException.class, () -> service.executeIdempotentTransfer(
                "key", UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN)));
    }
}
