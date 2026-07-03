package com.example.ApexPay;

import com.example.ApexPay.entity.Account;
import com.example.ApexPay.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Spring Security protects the payment API: unauthenticated calls are
 * rejected (401), the API user can transact, and validation failures return a
 * clean 400 with field-level detail. Broker-free: a @Primary mock RabbitTemplate
 * shadows the real amqpTemplate so the outbox path never touches a live broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityTests {

    @TestConfiguration
    static class MockRabbitTemplateConfig {

        @Bean
        @Primary
        RabbitTemplate mockRabbitTemplate() {
            return Mockito.mock(RabbitTemplate.class);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void transferWithoutAuthenticationIsRejected() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = transferBody(UUID.randomUUID(), UUID.randomUUID(), "10.00");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transferWithApiUserAndValidBodySucceeds() {
        Account source = newAccount("500.00");
        Account destination = newAccount("0.00");

        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = transferBody(source.getId(), destination.getId(), "25.00");

        ResponseEntity<String> response = restTemplate
                .withBasicAuth("apiuser", "changeit")
                .postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("COMPLETED");
    }

    @Test
    void transferWithInvalidBodyReturns400WithFieldErrors() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        // Negative amount -> bean validation failure.
        String body = transferBody(UUID.randomUUID(), UUID.randomUUID(), "-5.00");

        ResponseEntity<String> response = restTemplate
                .withBasicAuth("apiuser", "changeit")
                .postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("fieldErrors").contains("amount");
    }

    @Test
    void transferMissingIdempotencyKeyReturns400() {
        Account source = newAccount("100.00");
        Account destination = newAccount("0.00");

        HttpHeaders headers = jsonHeaders(); // no Idempotency-Key
        String body = transferBody(source.getId(), destination.getId(), "10.00");

        ResponseEntity<String> response = restTemplate
                .withBasicAuth("apiuser", "changeit")
                .postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Idempotency-Key");
    }

    @Test
    void createAccountWithBadCurrencyReturns400() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {"userId":"%s","initialBalance":100.00,"currency":"dollars"}
                """.formatted(UUID.randomUUID());

        ResponseEntity<String> response = restTemplate
                .withBasicAuth("apiuser", "changeit")
                .postForEntity("/api/v1/accounts", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("currency");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String transferBody(UUID source, UUID destination, String amount) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s}
                """.formatted(source, destination, amount);
    }

    private Account newAccount(String balance) {
        Account account = new Account();
        account.setUserId(UUID.randomUUID());
        account.setBalance(new BigDecimal(balance));
        account.setCurrency("USD");
        return accountRepository.save(account);
    }
}
