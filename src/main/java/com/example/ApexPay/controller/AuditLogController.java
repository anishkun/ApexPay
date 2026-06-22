package com.example.ApexPay.controller;

import com.example.ApexPay.entity.AuditLog;
import com.example.ApexPay.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view over the immutable audit trail. Useful for confirming, end to
 * end, that account creation and transfers were recorded.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    // All audit entries for a given account (or transaction) id, newest first not guaranteed.
    @GetMapping("/{entityId}")
    public ResponseEntity<List<AuditLog>> getByEntity(@PathVariable UUID entityId) {
        return ResponseEntity.ok(auditLogRepository.findByEntityId(entityId));
    }
}
