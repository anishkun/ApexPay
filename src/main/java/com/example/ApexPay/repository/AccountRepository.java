package com.example.ApexPay.repository;

import com.example.ApexPay.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Spring Data JPA will automatically implement this interface
}