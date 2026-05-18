package com.example.ApexPay.repository;

import com.example.ApexPay.entity.AuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Extending Repository instead of JpaRepository hides dangerous methods like delete()
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    // Only allow saving new records
    AuditLog save(AuditLog auditLog);

    // Only allow reading records
    Optional<AuditLog> findById(UUID id);
    List<AuditLog> findAll();
    List<AuditLog> findByEntityId(UUID entityId);
}