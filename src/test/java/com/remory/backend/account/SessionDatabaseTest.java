package com.remory.backend.account;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=${REMORY_TEST_JDBC_URL}",
        "spring.datasource.username=${REMORY_TEST_DB_USER:remory}",
        "spring.datasource.password=${REMORY_TEST_DB_PASSWORD:remory-local}"
})
@AutoConfigureMockMvc
@ActiveProfiles("session-integration")
@EnabledIfEnvironmentVariable(named = "REMORY_TEST_JDBC_URL", matches = "jdbc:mysql:.*")
class SessionDatabaseTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockitoBean GoogleIdentityVerifier google;
    @Autowired SessionRepository sessions;

    @Test
    void restartRestoresUsingOnlyPersistentCookieAndPreservesLibrary() throws Exception {
        Login initial = signup(false);
        String header = initial.result().getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/auth", "Max-Age=").doesNotContain("Secure");
        assertThat(cookieMaxAge(initial.result())).isBetween(399L * 86400, 400L * 86400);
        assertThat(initial.result().getResponse().getContentAsString()).doesNotContain("refreshToken", "refreshExpiresAt", initial.cookie().getValue());
        assertThat(initial.session().expiresAt()).isBetween(Instant.now().plusSeconds(880), Instant.now().plusSeconds(920));
        String deckId = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/study/decks").header("Authorization", bearer(initial))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "id", deckId, "title", "Remember this", "subtitle", "", "theme", "blue", "icon", "book"))))
                .andExpect(status().isOk());
        expireAccess(initial);
        mvc.perform(get("/api/v1/study").header("Authorization", bearer(initial))).andExpect(status().isUnauthorized());
        Login restored = session(refresh(initial.cookie(), 200));
        assertThat(restored.session().account().id()).isEqualTo(initial.session().account().id());
        assertThat(restored.cookie().getValue()).isNotEqualTo(initial.cookie().getValue());
        mvc.perform(get("/api/v1/study").header("Authorization", bearer(restored)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decks[0].id").value(deckId));
        remove(initial);
    }

    @Test
    void longInactivitySurvivesCleanupAndStillRestoresAccount() throws Exception {
        Login initial = signup(false);
        String accountId = initial.session().account().id();
        jdbc.sql("UPDATE account_refresh_families SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 120 DAY WHERE account_id = :id")
                .param("id", accountId).update();
        expireAccess(initial);
        sessions.deleteExpired();
        assertThat(count("account_refresh_families", accountId)).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_refresh_families WHERE account_id = :id AND expires_at IS NULL")
                .param("id", accountId).query(Integer.class).single()).isOne();
        Login restored = session(refresh(initial.cookie(), 200));
        assertThat(restored.session().account().id()).isEqualTo(accountId);
        assertThat(cookieMaxAge(restored.result())).isBetween(399L * 86400, 400L * 86400);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(restored))).andExpect(status().isOk());
        remove(initial);
    }

    @Test
    void cleanupPrunesConsumedHistoryWithoutDeletingCurrentCredential() throws Exception {
        Login initial = signup(false);
        Login renewed = session(refresh(initial.cookie(), 200));
        byte[] oldHash = PasswordResetService.hash(initial.cookie().getValue());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_refresh_tokens WHERE token_hash = :hash AND used_at IS NOT NULL AND expires_at IS NOT NULL")
                .param("hash", oldHash).query(Integer.class).single()).isOne();
        jdbc.sql("UPDATE account_refresh_tokens SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE token_hash = :hash")
                .param("hash", oldHash).update();
        sessions.deleteExpired();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_refresh_tokens WHERE token_hash = :hash")
                .param("hash", oldHash).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_refresh_tokens WHERE token_hash = :hash AND expires_at IS NULL")
                .param("hash", PasswordResetService.hash(renewed.cookie().getValue())).query(Integer.class).single()).isOne();
        refresh(initial.cookie(), 401);
        refresh(renewed.cookie(), 200);
        remove(initial);
    }

    @Test
    void httpsSignupSetsSecureCookie() throws Exception {
        Login login = signup(true);
        assertThat(login.result().getResponse().getHeader("Set-Cookie")).contains("Secure", "HttpOnly");
        remove(login);
    }

    @Test
    void simultaneousRefreshHasOneWinnerAndDoesNotEraseNewCookie() throws Exception {
        Login initial = signup(false);
        List<Callable<MvcResult>> calls = List.of(() -> rawRefresh(initial.cookie()), () -> rawRefresh(initial.cookie()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(calls);
            var results = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200, 409);
            var conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(conflict.getResponse().getHeader("Set-Cookie")).isNull();
            Login winner = session(results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow());
            refresh(winner.cookie(), 200);
        }
        remove(initial);
    }

    @Test
    void replayRevocationCommitsAndDoesNotAffectAnotherDevice() throws Exception {
        Login initial = signup(false);
        Login otherDevice = login(initial.session().account().email());
        Login renewed = session(refresh(initial.cookie(), 200));
        jdbc.sql("UPDATE account_refresh_tokens SET used_at = CURRENT_TIMESTAMP(6) - INTERVAL 20 SECOND WHERE token_hash = :hash")
                .param("hash", PasswordResetService.hash(initial.cookie().getValue())).update();
        refresh(initial.cookie(), 401);
        refresh(renewed.cookie(), 401);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(renewed))).andExpect(status().isUnauthorized());
        refresh(otherDevice.cookie(), 200);
        remove(initial);
    }

    @Test
    void logoutRevokesThisDeviceAndClearsCookieEvenAfterAccessExpiry() throws Exception {
        Login initial = signup(false);
        Login other = login(initial.session().account().email());
        expireAccess(initial);
        mvc.perform(post("/api/v1/auth/logout").cookie(initial.cookie()).header("X-Remory-Client", "web"))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(initial.cookie(), 401);
        refresh(other.cookie(), 200);
        mvc.perform(post("/api/v1/auth/logout").header("X-Remory-Client", "web")).andExpect(status().isNoContent());
        remove(initial);
    }

    @Test
    void expiredFamilyRejectsAccessAndRefreshBeforeScheduledCleanup() throws Exception {
        Login initial = signup(false);
        jdbc.sql("UPDATE account_refresh_families SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE account_id = :id")
                .param("id", initial.session().account().id()).update();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(initial))).andExpect(status().isUnauthorized());
        refresh(initial.cookie(), 401);
        assertThat(count("account_refresh_families", initial.session().account().id())).isZero();
        remove(initial);
    }

    @Test
    void passwordRecoveryRevokesPersistentLoginOnEveryDevice() throws Exception {
        Login initial = signup(false);
        Login other = login(initial.session().account().email());
        String challenge = token();
        String grant = token();
        jdbc.sql("""
                INSERT INTO password_reset_challenges
                (challenge_hash, email_hash, account_id, code_hash, expires_at, verified_token_hash, verified_expires_at)
                VALUES (:challenge, :email, :id, :code, CURRENT_TIMESTAMP(6) + INTERVAL 10 MINUTE, :grant, CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE)
                """).param("challenge", PasswordResetService.hash(challenge)).param("email", PasswordResetService.hash(initial.session().account().email()))
                .param("id", initial.session().account().id()).param("code", PasswordResetService.codeHash(challenge, "000123"))
                .param("grant", PasswordResetService.hash(grant)).update();
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("token", grant, "password", "new-password"))))
                .andExpect(status().isNoContent());
        refresh(initial.cookie(), 401);
        refresh(other.cookie(), 401);
        assertThat(count("account_refresh_families", initial.session().account().id())).isZero();
        remove(initial);
    }

    @Test
    void accountDeletionCascadesAllPersistentSessions() throws Exception {
        Login initial = signup(false);
        mvc.perform(post("/api/v1/auth/me/delete").header("Authorization", bearer(initial)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("password", "old-password")))).andExpect(status().isNoContent());
        refresh(initial.cookie(), 401);
        assertThat(count("account_refresh_families", initial.session().account().id())).isZero();
    }

    @Test
    void legacyAccessCanBeUpgradedOnceWithoutPassword() throws Exception {
        Login initial = signup(false);
        String legacy = token();
        jdbc.sql("INSERT INTO account_sessions (token_hash, account_id, expires_at) VALUES (:hash, :id, CURRENT_TIMESTAMP(6) + INTERVAL 14 DAY)")
                .param("hash", PasswordResetService.hash(legacy)).param("id", initial.session().account().id()).update();
        MvcResult result = mvc.perform(post("/api/v1/auth/session").header("Authorization", "Bearer " + legacy).header("X-Remory-Client", "web"))
                .andExpect(status().isOk()).andReturn();
        Login upgraded = session(result);
        refresh(upgraded.cookie(), 200);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/session").header("Authorization", "Bearer " + legacy).header("X-Remory-Client", "web"))
                .andExpect(status().isUnauthorized());
        remove(initial);
    }

    @Test
    void cookieOnlyActionsRejectCrossSiteOrMissingClientHeader() throws Exception {
        Login initial = signup(false);
        mvc.perform(post("/api/v1/auth/refresh").cookie(initial.cookie())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").cookie(initial.cookie()).header("X-Remory-Client", "web").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        refresh(initial.cookie(), 200);
        remove(initial);
    }

    @Test
    void googleLoginAlsoIssuesPersistentCookieAndRestoresWithoutGooglePopup() throws Exception {
        when(google.clientId()).thenReturn("session-test.apps.googleusercontent.com");
        MvcResult challenge = mvc.perform(get("/api/v1/auth/google/challenge")).andExpect(status().isOk()).andReturn();
        String nonce = json.readTree(challenge.getResponse().getContentAsString()).get("nonce").asText();
        String subject = UUID.randomUUID().toString();
        when(google.verify("fixture", nonce)).thenReturn(new GoogleIdentityVerifier.Identity(subject, subject + "@example.com", nonce));
        Cookie browser = cookie(challenge, "remory_google_browser");
        Login initial = session(mvc.perform(post("/api/v1/auth/google").cookie(browser).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("credential", "fixture", "nonce", nonce))))
                .andExpect(status().isOk()).andReturn());
        assertThat(initial.session().account().googleLinked()).isTrue();
        Login restored = session(refresh(initial.cookie(), 200));
        assertThat(restored.session().account().id()).isEqualTo(initial.session().account().id());
        remove(initial);
    }

    private Login signup(boolean secure) throws Exception {
        return session(mvc.perform(post("/api/v1/auth/signup").secure(secure).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", UUID.randomUUID() + "@example.com", "password", "old-password"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn());
    }
    private Login login(String email) throws Exception {
        return session(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "password", "old-password"))))
                .andExpect(status().isOk()).andReturn());
    }
    private MvcResult rawRefresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(cookie).header("X-Remory-Client", "web")).andReturn();
    }
    private MvcResult refresh(Cookie cookie, int expected) throws Exception {
        MvcResult result = rawRefresh(cookie);
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return result;
    }
    private Login session(MvcResult result) throws Exception {
        return new Login(json.readValue(result.getResponse().getContentAsString(), AccountService.Session.class), cookie(result, "remory_refresh"), result);
    }
    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }
    private static long cookieMaxAge(MvcResult result) {
        return java.util.Arrays.stream(result.getResponse().getHeader("Set-Cookie").split(";"))
                .map(String::trim).filter(value -> value.startsWith("Max-Age="))
                .mapToLong(value -> Long.parseLong(value.substring("Max-Age=".length()))).findFirst().orElseThrow();
    }
    private static String bearer(Login login) { return "Bearer " + login.session().accessToken(); }
    private static String token() { return UUID.randomUUID().toString().replace("-", "") + "x".repeat(11); }
    private void expireAccess(Login login) {
        jdbc.sql("UPDATE account_sessions SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE token_hash = :hash")
                .param("hash", PasswordResetService.hash(login.session().accessToken())).update();
    }
    private int count(String table, String accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE account_id = :id").param("id", accountId).query(Integer.class).single();
    }
    private void remove(Login login) { jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", login.session().account().id()).update(); }
    private record Login(AccountService.Session session, Cookie cookie, MvcResult result) {}
}
