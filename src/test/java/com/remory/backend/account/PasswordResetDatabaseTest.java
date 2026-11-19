package com.remory.backend.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=${REMORY_TEST_JDBC_URL}",
        "spring.datasource.username=${REMORY_TEST_DB_USER:remory}",
        "spring.datasource.password=${REMORY_TEST_DB_PASSWORD:remory-local}"
})
@AutoConfigureMockMvc
@ActiveProfiles("reset-integration")
@EnabledIfEnvironmentVariable(named = "REMORY_TEST_JDBC_URL", matches = "jdbc:mysql:.*")
class PasswordResetDatabaseTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockitoBean PasswordResetMail mail;
    @MockitoBean(name = "passwordResetExecutor") ThreadPoolTaskExecutor executor;
    private final AtomicReference<String> code = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(mail.available()).thenReturn(true);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
        doAnswer(call -> { code.set(call.getArgument(1)); return null; }).when(mail).send(any(), any(), any());
    }

    @Test
    void codeThenGrantResetsPasswordPreservesStudyAndRejectsAllReplays() throws Exception {
        var session = signup();
        String deckId = UUID.randomUUID().toString();
        String cardId = UUID.randomUUID().toString();
        var data = Map.of(
                "decks", List.of(Map.of("id", deckId, "title", "Keep this deck", "subtitle", "", "theme", "blue", "icon", "book")),
                "cards", List.of(Map.of("id", cardId, "deckId", deckId, "front", "Question", "back", "Answer",
                        "dueAt", "2030-01-01T00:00:00Z", "repetitions", 3, "kind", "basic")),
                "logs", List.of(Map.of("id", UUID.randomUUID().toString(), "cardId", cardId,
                        "rating", "good", "date", "2026-01-01T00:00:00Z", "mode", "scheduled")));
        mvc.perform(post("/api/v1/study/import").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(data))).andExpect(status().isNoContent());
        var challenge = request(session.account().email(), "10.0.0.1");
        String sentCode = code.get();
        assertThat(sentCode).matches("[0-9]{6}");
        assertThat(jdbc.sql("SELECT code_hash FROM password_reset_challenges WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(challenge.challengeId())).query(byte[].class).single())
                .isEqualTo(PasswordResetService.codeHash(challenge.challengeId(), sentCode));
        confirm(challenge.challengeId(), "new-password", 400);
        var verification = verifyCode(challenge.challengeId(), sentCode, 200);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.accessToken())).andExpect(status().isOk());
        login(session.account().email(), "old-password", 200);
        verifyCode(challenge.challengeId(), sentCode, 400);
        confirm(verification.resetToken(), "new-password", 204);
        confirm(verification.resetToken(), "third-password", 400);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.accessToken())).andExpect(status().isUnauthorized());
        login(session.account().email(), "old-password", 401);
        var next = login(session.account().email(), "new-password", 200);
        mvc.perform(get("/api/v1/study").header("Authorization", "Bearer " + next.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].repetitions").value(3))
                .andExpect(jsonPath("$.cards[0].dueAt").value("2030-01-01T00:00:00Z"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_logs WHERE account_id = :id")
                .param("id", session.account().id()).query(Integer.class).single()).isEqualTo(1);
        remove(session.account().id());
    }

    @Test
    void concurrentWrongGuessesPersistExactlyFiveAttemptsAndResendInvalidatesOldCode() throws Exception {
        var session = signup();
        var challenge = request(session.account().email(), "10.0.0.2");
        String actual = code.get();
        String wrong = actual.equals("000000") ? "000001" : "000000";
        var tasks = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 6; i++) tasks.add(() -> codeResult(challenge.challengeId(), wrong).getResponse().getStatus());
        var statuses = new ArrayList<Integer>();
        try (var pool = Executors.newFixedThreadPool(3)) {
            for (var future : pool.invokeAll(tasks)) statuses.add(future.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(400, 400, 400, 400, 429, 429);
        assertThat(jdbc.sql("SELECT attempts FROM password_reset_challenges WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(challenge.challengeId())).query(Integer.class).single()).isEqualTo(5);
        verifyCode(challenge.challengeId(), actual, 429);
        clearLimits();
        var next = request(session.account().email(), "10.0.0.2");
        String newCode = code.get();
        verifyCode(challenge.challengeId(), actual, 400);
        var verification = verifyCode(next.challengeId(), newCode, 200);
        jdbc.sql("UPDATE password_reset_challenges SET verified_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(next.challengeId())).update();
        confirm(verification.resetToken(), "new-password", 400);
        login(session.account().email(), "old-password", 200);
        remove(session.account().id());
    }

    @Test
    void failedDeliveryRollsBackAssociationAndExpiryCannotChangePassword() throws Exception {
        var session = signup();
        var original = request(session.account().email(), "10.0.0.3");
        String originalCode = code.get();
        clearLimits();
        doThrow(new MailSendException("test failure")).when(mail).send(any(), any(), any());
        var failed = request(session.account().email(), "10.0.0.3");
        assertThat(jdbc.sql("SELECT account_id FROM password_reset_challenges WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(failed.challengeId())).query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT account_id FROM password_reset_challenges WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(original.challengeId())).query(String.class).single()).isEqualTo(session.account().id());
        jdbc.sql("UPDATE password_reset_challenges SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE challenge_hash = :hash")
                .param("hash", PasswordResetService.hash(original.challengeId())).update();
        verifyCode(original.challengeId(), originalCode, 400);
        login(session.account().email(), "old-password", 200);
        remove(session.account().id());
    }

    @Test
    void unknownAndGoogleOnlyAddressesNeverSendCodesOrGetResetGrants() throws Exception {
        String id = UUID.randomUUID().toString();
        String email = id + "@example.com";
        jdbc.sql("INSERT INTO accounts (id, email) VALUES (:id, :email)").param("id", id).param("email", email).update();
        jdbc.sql("INSERT INTO account_identities (provider, subject, account_id) VALUES ('google', :subject, :id)")
                .param("subject", id).param("id", id).update();
        for (String address : List.of(email, UUID.randomUUID() + "@example.com")) {
            var challenge = request(address, "10.0.0.4");
            assertThat(challenge.challengeId()).hasSize(43);
            verifyCode(challenge.challengeId(), "000000", 400);
            confirm(challenge.challengeId(), "new-password", 400);
        }
        verify(mail, never()).send(any(), any(), any());
        remove(id);
    }

    private AccountService.Session signup() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", UUID.randomUUID() + "@example.com", "password", "old-password"))))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(response.getResponse().getContentAsString(), AccountService.Session.class);
    }

    private AccountService.Session login(String email, String password, int expected) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "password", password))))
                .andExpect(status().is(expected)).andReturn();
        return expected == 200 ? json.readValue(response.getResponse().getContentAsString(), AccountService.Session.class) : null;
    }

    private PasswordResetService.RequestChallenge request(String email, String remote) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/password-reset/request").with(request -> { request.setRemoteAddr(remote); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("email", email, "locale", "ko"))))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readValue(response.getResponse().getContentAsString(), PasswordResetService.RequestChallenge.class);
    }

    private MvcResult codeResult(String challenge, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/password-reset/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("challengeId", challenge, "code", code)))).andReturn();
    }

    private PasswordResetService.Verification verifyCode(String challenge, String code, int expected) throws Exception {
        var result = codeResult(challenge, code);
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return expected == 200 ? json.readValue(result.getResponse().getContentAsString(), PasswordResetService.Verification.class) : null;
    }

    private void confirm(String token, String password, int expected) throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("token", token, "password", password))))
                .andExpect(status().is(expected));
    }

    private void clearLimits() {
        // This class only runs against a dedicated, disposable database.
        jdbc.sql("DELETE FROM password_reset_request_limits").update();
    }

    private void remove(String id) {
        jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", id).update();
    }
}
