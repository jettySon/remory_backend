package com.remory.backend.account;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Component
public class GoogleIdentityVerifier {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private final JwtDecoder decoder;
    private final String clientId;

    @Autowired
    public GoogleIdentityVerifier(@Value("${remory.google.client-id}") String clientId) {
        this(NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build(), clientId);
    }

    GoogleIdentityVerifier(JwtDecoder decoder, String clientId) {
        this.decoder = decoder;
        this.clientId = clientId;
    }

    String clientId() {
        return clientId;
    }

    public Identity verify(String credential, String expectedNonce) {
        try {
            Jwt token = decoder.decode(credential);
            String subject = token.getSubject();
            String nonce = token.getClaimAsString("nonce");
            String email = token.getClaimAsString("email");
            String issuer = token.getClaimAsString("iss");
            String authorizedParty = token.getClaimAsString("azp");
            Instant now = Instant.now();
            // Check the recipient, identity, expiry and nonce after signature verification.
            if (issuer == null || !ISSUERS.contains(issuer) || token.getAudience() == null || !token.getAudience().contains(clientId)
                    || (authorizedParty != null && !clientId.equals(authorizedParty))
                    || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now)
                    || token.getIssuedAt() == null || token.getIssuedAt().isAfter(now.plusSeconds(60))
                    || subject == null || !SUBJECT.matcher(subject).matches()
                    || nonce == null || !NONCE.matcher(nonce).matches() || !nonce.equals(expectedNonce)
                    || !Boolean.TRUE.equals(token.getClaim("email_verified"))
                    || email == null || email.length() > 254 || email.indexOf('@') < 1
                    || email.endsWith("@") || email.chars().anyMatch(Character::isWhitespace)) {
                throw AccountService.googleError(HttpStatus.UNAUTHORIZED, "GOOGLE_INVALID");
            }
            return new Identity(subject, email, nonce);
        } catch (JwtException | IllegalArgumentException error) {
            throw AccountService.googleError(HttpStatus.UNAUTHORIZED, "GOOGLE_INVALID");
        }
    }

    public record Identity(String subject, String email, String nonce) {}
}
