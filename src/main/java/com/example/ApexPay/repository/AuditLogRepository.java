package com.example.ApexPay.repository;

import com.example.ApexPay.entity.AuditLog; // Make sure this is imported
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// CHANGE Account TO AuditLog HERE
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}