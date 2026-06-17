package com.geminibank.ledger;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the ledger HTTP API, covering the contracts the Python
 * statements and frontend services depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerControllerTest {

    private static final String SECRET = "test-secret";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDb() {
        // Fresh state per test (one temp DB is shared by the test class).
        jdbc.execute("DELETE FROM transactions");
        jdbc.execute("DELETE FROM accounts");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempFile("ledger-test", ".db");
        Files.deleteIfExists(db); // start from a fresh schema
        registry.add("LEDGER_DB", db::toString);
        registry.add("TOKEN_SECRET", () -> SECRET);
    }

    // Mint a raw HS256 JWT exactly as PyJWT (in the users service) would, with no
    // minimum-key-length restriction. This is what the ledger must accept in
    // production, so the test signs the same way rather than via jjwt's builder.
    private static String token() {
        try {
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            String header = url.encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = url.encodeToString(
                    "{\"user_id\":1,\"full_name\":\"Test User\"}".getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = url.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String bearer() {
        return "Bearer " + token();
    }

    private int openAccount(String name) throws Exception {
        String body = mvc.perform(post("/accounts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    private void deposit(int accountId, String amount) throws Exception {
        mvc.perform(post("/accounts/" + accountId + "/deposits")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"" + amount + "\",\"description\":\"seed\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void healthz() throws Exception {
        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
    }

    @Test
    void openAccountRequiresAuth() throws Exception {
        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("unauthorized")));
    }

    @Test
    void depositHappyPathUpdatesBalance() throws Exception {
        int id = openAccount("Checking");
        mvc.perform(post("/accounts/" + id + "/deposits")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100.50\",\"description\":\"pay\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("deposit")))
                .andExpect(jsonPath("$.amount", is(100.50)));

        mvc.perform(get("/accounts/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(100.50)));
    }

    @Test
    void listAccountsForUser() throws Exception {
        openAccount("A");
        openAccount("B");
        mvc.perform(get("/accounts?user_id=1").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        int id = openAccount("Checking");
        deposit(id, "10.00");
        mvc.perform(post("/accounts/" + id + "/payments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"50.00\",\"beneficiary\":{\"type\":\"external\",\"iban\":\"DE99\",\"name\":\"X\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("insufficient_funds")));
    }

    @Test
    void internalPaymentIsAtomic() throws Exception {
        int src = openAccount("Source");
        int dst = openAccount("Dest");
        deposit(src, "300.00");
        mvc.perform(post("/accounts/" + src + "/payments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"120.00\",\"description\":\"rent\","
                                + "\"beneficiary\":{\"type\":\"internal\",\"account_id\":" + dst + "}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("payment_out")));

        mvc.perform(get("/accounts/" + src).header("Authorization", bearer()))
                .andExpect(jsonPath("$.balance", is(180.00)));
        mvc.perform(get("/accounts/" + dst).header("Authorization", bearer()))
                .andExpect(jsonPath("$.balance", is(120.00)));

        // Destination shows a matching payment_in in the feed consumed by statements.
        mvc.perform(get("/internal/transactions?account_id=" + dst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_id", is(dst)))
                .andExpect(jsonPath("$.transactions[0].type", is("payment_in")))
                .andExpect(jsonPath("$.transactions[0].amount", is(120.00)));
    }

    @Test
    void badAmountReturns400() throws Exception {
        int id = openAccount("Checking");
        for (String bad : new String[]{"-5.00", "0", "abc", "1.234"}) {
            mvc.perform(post("/accounts/" + id + "/deposits")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("invalid_amount")));
        }
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(get("/accounts?user_id=1").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }
}
