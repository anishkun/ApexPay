package com.example.ApexPay.repository;

import com.example.ApexPay.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

// Note: The second generic type is 'String' because our @Id is the idempotencyKey string!
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}