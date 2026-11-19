package com.remory.backend.account;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdentityVerifierTest {

    private static final String CLIENT = "remory-test.apps.googleusercontent.com";
    private static final String NONCE = "n".repeat(43);
    private static KeyPair trusted;
    private static KeyPair other;
    private static GoogleIdentityVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        other = generator.generateKeyPair();
        verifier = new GoogleIdentityVerifier(
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic()).build(), CLIENT);
    }

    private String token(Map<String, Object> overrides, KeyPair signingKey) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com").audience(CLIENT).subject("google-user-123")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("email", "person@example.com").claim("email_verified", true).claim("nonce", NONCE);
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) signingKey.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void acceptsSignedTokenForThisClientAndNonce() throws Exception {
        var identity = verifier.verify(token(Map.of(), trusted), NONCE);
        assertThat(identity.subject()).isEqualTo("google-user-123");
        assertThat(identity.email()).isEqualTo("person@example.com");
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        assertInvalid(token(Map.of(), other), NONCE);
    }

    @Test
    void rejectsWrongAudienceAndIssuer() throws Exception {
        assertInvalid(token(Map.of("aud", "someone-else"), trusted), NONCE);
        assertInvalid(token(Map.of("iss", "https://attacker.example"), trusted), NONCE);
    }

    @Test
    void rejectsMissingRequiredClaims() throws Exception {
        for (String claim : new String[] { "aud", "iss", "sub", "exp", "iat", "email", "email_verified", "nonce" }) {
            assertInvalid(token(Collections.singletonMap(claim, null), trusted), NONCE);
        }
    }

    @Test
    void rejectsExpiredOrFutureToken() throws Exception {
        assertInvalid(token(Map.of("exp", Date.from(Instant.now().minusSeconds(120))), trusted), NONCE);
        assertInvalid(token(Map.of("iat", Date.from(Instant.now().plusSeconds(180))), trusted), NONCE);
    }

    @Test
    void rejectsUnverifiedEmailAndWrongNonce() throws Exception {
        assertInvalid(token(Map.of("email_verified", false), trusted), NONCE);
        assertInvalid(token(Map.of(), trusted), "different-nonce");
    }

    @Test
    void rejectsWrongAuthorizedPartyAndMalformedCredential() throws Exception {
        assertInvalid(token(Map.of("azp", "other-client"), trusted), NONCE);
        assertInvalid("this-is-not-a-jwt", NONCE);
    }

    private void assertInvalid(String token, String nonce) {
        assertThatThrownBy(() -> verifier.verify(token, nonce))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
    }
}
