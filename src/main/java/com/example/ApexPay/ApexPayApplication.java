


package com.example.ApexPay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone; // Add this import

@SpringBootApplication
@EnableScheduling
public class ApexPayApplication {

    public static void main(String[] args) {
        // This forces the app to use UTC, bypassing the Windows/Postgres mismatch
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ApexPayApplication.class, args);
    }
}