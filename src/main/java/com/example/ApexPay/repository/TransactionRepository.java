package com.example.ApexPay.repository;

import com.example.ApexPay.entity.Transaction; // Make sure this is imported
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// CHANGE Account TO Transaction HERE
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
}