package com.remory.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private static final Duration SESSION_LIFETIME = Duration.ofDays(14);
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final JdbcTemplate database;
    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String unknownAccountHash = passwords.encode("unregistered-account");
    private final SecureRandom random = new SecureRandom();

    public AccountService(JdbcTemplate database, JdbcClient jdbc) {
        this.database = database;
        this.jdbc = jdbc;
    }

    @Transactional
    public Session signup(String email, String password) {
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상, UTF-8 기준 72바이트 이하여야 해요.");
        }

        var account = new Account(UUID.randomUUID().toString(), normalizeEmail(email));
        String passwordHash = passwords.encode(password);
        try {
            database.update("INSERT INTO accounts (id, email) VALUES (?, ?)", account.id(), account.email());
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일이에요.");
        }
        database.update("INSERT INTO account_passwords (account_id, password_hash) VALUES (?, ?)",
                account.id(), passwordHash);
        return issueSession(account);
    }

    public Session login(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        Credentials credentials = jdbc.sql("""
                SELECT a.id, a.email, p.password_hash
                FROM accounts a JOIN account_passwords p ON p.account_id = a.id
                WHERE a.email = :email
                """)
                .param("email", normalizeEmail(email))
                .query((row, index) -> new Credentials(
                        new Account(row.getString("id"), row.getString("email")), row.getString("password_hash")))
                .optional()
                .orElse(null);
        boolean correct = passwords.matches(password,
                credentials == null ? unknownAccountHash : credentials.passwordHash());
        if (!correct || credentials == null) {
            throw invalidCredentials();
        }
        return issueSession(credentials.account());
    }

    public Account authenticate(String authorization) {
        byte[] tokenHash = authorizationHash(authorization);
        return jdbc.sql("""
                SELECT a.id, a.email FROM account_sessions s
                JOIN accounts a ON a.id = s.account_id
                WHERE s.token_hash = :tokenHash AND s.expires_at > CURRENT_TIMESTAMP(6)
                """)
                .param("tokenHash", tokenHash)
                .query((row, index) -> new Account(row.getString("id"), row.getString("email")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."));
    }

    public void logout(String authorization) {
        database.update("DELETE FROM account_sessions WHERE token_hash = ?", authorizationHash(authorization));
    }

    @Scheduled(fixedDelayString = "PT6H")
    void removeExpiredSessions() {
        database.update("DELETE FROM account_sessions WHERE expires_at <= CURRENT_TIMESTAMP(6)");
    }

    private Session issueSession(Account account) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        database.update("""
                INSERT INTO account_sessions (token_hash, account_id, expires_at)
                VALUES (?, ?, ?)
                """, hash(token), account.id(), Timestamp.from(expiresAt));
        return new Session(account, token, expiresAt);
    }

    private static byte[] authorizationHash(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요.");
        }
        String token = authorization.substring(7);
        if (!BEARER_TOKEN.matcher(token).matches()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요.");
        }
        return hash(token);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요.");
    }

    public record Account(String id, String email) {}
    public record Session(Account account, String accessToken, Instant expiresAt) {}
    private record Credentials(Account account, String passwordHash) {}
}
