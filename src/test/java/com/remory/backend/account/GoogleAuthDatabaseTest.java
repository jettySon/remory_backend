package com.remory.backend.account;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=${REMORY_TEST_JDBC_URL}",
        "spring.datasource.username=${REMORY_TEST_DB_USER:remory}",
        "spring.datasource.password=${REMORY_TEST_DB_PASSWORD:remory-local}"
})
@AutoConfigureMockMvc
@ActiveProfiles("google-integration")
@EnabledIfEnvironmentVariable(named = "REMORY_TEST_JDBC_URL", matches = "jdbc:mysql:.*")
@Import(GoogleAuthDatabaseTest.TrustedGoogle.class)
class GoogleAuthDatabaseTest {

    private static final String CLIENT = "database-test.apps.googleusercontent.com";
    private static final KeyPair KEY = keyPair();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TrustedGoogle {
        @Bean
        @Primary
        GoogleIdentityVerifier trustedGoogle() {
            return new GoogleIdentityVerifier(
                    NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build(), CLIENT);
        }
    }

    @Test
    void googleSignupImportsHistoryAndPublicDeletionCascades() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = subject + "@example.com";
        Challenge login = challenge("login");
        Map<String, Object> proof = proof(login, subject, email);
        AccountService.Session session = login(proof, login.cookie());
        assertThat(session.account().hasPassword()).isFalse();
        assertThat(session.account().googleLinked()).isTrue();

        String deckId = UUID.randomUUID().toString();
        String cardId = UUID.randomUUID().toString();
        Map<String, Object> guest = Map.of(
                "decks", List.of(Map.of("id", deckId, "title", "My deck", "subtitle", "", "theme", "blue", "icon", "book")),
                "cards", List.of(Map.of("id", cardId, "deckId", deckId, "front", "Question", "back", "Answer",
                        "dueAt", "2030-01-01T00:00:00Z", "repetitions", 3, "kind", "basic")),
                "logs", List.of(Map.of("id", UUID.randomUUID().toString(), "cardId", cardId,
                        "rating", "good", "date", "2026-01-01T00:00:00Z", "mode", "scheduled")));
        mvc.perform(body(post("/api/v1/study/import").header("Authorization", bearer(session)), guest))
                .andExpect(status().isNoContent());
        mvc.perform(body(post("/api/v1/study/import").header("Authorization", bearer(session)), guest))
                .andExpect(status().isNoContent());

        Challenge returning = challenge("login");
        AccountService.Session next = login(proof(returning, subject, "changed-" + email), returning.cookie());
        assertThat(next.account().id()).isEqualTo(session.account().id());
        mvc.perform(get("/api/v1/study").header("Authorization", bearer(next)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].repetitions").value(3))
                .andExpect(jsonPath("$.cards[0].dueAt").value("2030-01-01T00:00:00Z"));

        Challenge removal = challenge("delete");
        mvc.perform(body(post("/api/v1/auth/google/account-deletion").cookie(removal.cookie()),
                        proof(removal, subject, email)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(session.account().id()));
        for (String table : List.of("account_identities", "account_sessions", "decks", "cards", "review_logs")) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE account_id = :id")
                    .param("id", session.account().id()).query(Integer.class).single()).isZero();
        }
        mvc.perform(get("/api/v1/study").header("Authorization", bearer(next))).andExpect(status().isUnauthorized());
    }

    @Test
    void linkingRequiresPasswordAndFailedConfirmationPreservesNonce() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = subject + "@example.com";
        var signup = mvc.perform(body(post("/api/v1/auth/signup"),
                        Map.of("email", email, "password", "correct-password")))
                .andExpect(status().isCreated()).andReturn();
        var original = json.readValue(signup.getResponse().getContentAsString(), AccountService.Session.class);
        Challenge challenge = challenge("login");
        Map<String, Object> proof = proof(challenge, subject, email);
        mvc.perform(body(post("/api/v1/auth/google").cookie(challenge.cookie()), proof))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GOOGLE_LINK_REQUIRED"));
        proof.put("password", "wrong-password");
        mvc.perform(body(post("/api/v1/auth/google").cookie(challenge.cookie()), proof))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOOGLE_LINK_PASSWORD"));
        proof.put("password", "correct-password");
        AccountService.Session linked = login(proof, challenge.cookie());
        assertThat(linked.account().id()).isEqualTo(original.account().id());
        assertThat(linked.account().hasPassword()).isTrue();
        assertThat(linked.account().googleLinked()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(original)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.googleLinked").value(true));
        mvc.perform(body(post("/api/v1/auth/google").cookie(challenge.cookie()), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GOOGLE_RESTART"));
    }

    @Test
    void proofCannotBeUsedInAnotherBrowserOrForDeletion() throws Exception {
        String subject = UUID.randomUUID().toString();
        Challenge challenge = challenge("login");
        Map<String, Object> proof = proof(challenge, subject, subject + "@example.com");
        mvc.perform(body(post("/api/v1/auth/google").cookie(new Cookie("remory_google_browser", "x".repeat(43))), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GOOGLE_RESTART"));
        mvc.perform(body(post("/api/v1/auth/google/account-deletion").cookie(challenge.cookie()), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GOOGLE_RESTART"));
        assertThat(login(proof, challenge.cookie()).account().googleLinked()).isTrue();
    }

    private Challenge challenge(String purpose) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/google/challenge").param("purpose", purpose))
                .andExpect(status().isOk()).andReturn();
        var values = json.readTree(result.getResponse().getContentAsString());
        String cookie = result.getResponse().getHeader("Set-Cookie").split(";")[0];
        return new Challenge(values.get("nonce").asText(),
                new Cookie("remory_google_browser", cookie.substring(cookie.indexOf('=') + 1)));
    }

    private Map<String, Object> proof(Challenge challenge, String subject, String email) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com")
                .audience(CLIENT).subject(subject).claim("email", email).claim("email_verified", true)
                .claim("nonce", challenge.nonce()).issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return new HashMap<>(Map.of("credential", token.serialize(), "nonce", challenge.nonce()));
    }

    private AccountService.Session login(Map<String, Object> proof, Cookie cookie) throws Exception {
        var result = mvc.perform(body(post("/api/v1/auth/google").cookie(cookie), proof))
                .andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AccountService.Session.class);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
    }

    private static String bearer(AccountService.Session session) {
        return "Bearer " + session.accessToken();
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Challenge(String nonce, Cookie cookie) {}
}
