package com.example.ApexPay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransferApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private UUID createAccount(String balance) throws Exception {
        String body = String.format(
                "{\"userId\":\"%s\",\"initialBalance\":%s,\"currency\":\"USD\"}",
                UUID.randomUUID(), balance);
        String resp = mockMvc.perform(post("/api/v1/accounts").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("id").asText());
    }

    private String transferBody(UUID src, UUID dst, String amount) {
        return String.format(
                "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":%s}",
                src, dst, amount);
    }

    private BigDecimal balanceOf(UUID accountId) throws Exception {
        String resp = mockMvc.perform(get("/api/v1/accounts/" + accountId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(resp).get("balance").asText());
    }

    @Test
    void createTransferAndQueryEndToEnd() throws Exception {
        UUID src = createAccount("100.00");
        UUID dst = createAccount("0.00");

        String resp = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "40.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(objectMapper.readTree(resp).get("id").asText());

        // Query the payment back
        mockMvc.perform(get("/api/v1/transfers/" + txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(0, balanceOf(src).compareTo(new BigDecimal("60.00")));
        assertEquals(0, balanceOf(dst).compareTo(new BigDecimal("40.00")));

        // Audit trail: CREATED at account opening + DEBIT from the transfer
        mockMvc.perform(get("/api/v1/audit-logs/" + src))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void idempotentRetryReturnsSameTransaction() throws Exception {
        UUID src = createAccount("100.00");
        UUID dst = createAccount("0.00");
        String key = "k-" + UUID.randomUUID();

        String first = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "30.00")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String retry = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "30.00")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(objectMapper.readTree(first).get("id"), objectMapper.readTree(retry).get("id"));
        // Deducted only once
        assertEquals(0, balanceOf(src).compareTo(new BigDecimal("70.00")));
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        UUID src = createAccount("100.00");
        UUID dst = createAccount("0.00");
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "10.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientFundsIsUnprocessable() throws Exception {
        UUID src = createAccount("5.00");
        UUID dst = createAccount("0.00");
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "50.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transferToUnknownAccountIsNotFound() throws Exception {
        UUID src = createAccount("100.00");
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, UUID.randomUUID(), "10.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void negativeAmountFailsValidation() throws Exception {
        UUID src = createAccount("100.00");
        UUID dst = createAccount("0.00");
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(src, dst, "-5.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void queryingUnknownTransactionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
