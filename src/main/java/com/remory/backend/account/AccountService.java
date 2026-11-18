package com.remory.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private static final Duration SESSION_LIFETIME = Duration.ofDays(14);
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final AccountRepository repository;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String unknownAccountHash = passwords.encode("unregistered-account");
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Session signup(String email, String password) {
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상, UTF-8 기준 72바이트 이하여야 해요.");
        }

        var account = new Account(UUID.randomUUID().toString(), normalizeEmail(email));
        String passwordHash = passwords.encode(password);
        try {
            repository.insertAccount(account);
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일이에요.");
        }
        repository.insertPassword(account.id(), passwordHash);
        return issueSession(account);
    }

    @Transactional
    public Session login(String email, String password) {
        Account account = verifyCredentials(email, password);
        // 탈퇴와 동시에 로그인하면 계정 삭제 후 새 세션을 발급하지 않는다.
        if (!repository.lockAccount(account.id())) {
            throw invalidCredentials();
        }
        return issueSession(account);
    }

    @Transactional
    public void deleteSignedInAccount(String authorization, String password) {
        Account account = authenticate(authorization);
        String passwordHash = repository.findPasswordHash(account.id()).orElse(null);
        if (!matchesPassword(password, passwordHash)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "비밀번호를 확인해 주세요.");
        }
        repository.deleteAccount(account.id());
    }

    @Transactional
    public void deleteAccountByCredentials(String email, String password) {
        repository.deleteAccount(verifyCredentials(email, password).id());
    }

    public Account authenticate(String authorization) {
        byte[] tokenHash = authorizationHash(authorization);
        return repository.findActiveSessionAccount(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."));
    }

    public void logout(String authorization) {
        repository.deleteSession(authorizationHash(authorization));
    }

    @Scheduled(fixedDelayString = "PT6H")
    void removeExpiredSessions() {
        repository.deleteExpiredSessions();
    }

    private Session issueSession(Account account) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        repository.insertSession(account.id(), hash(token), expiresAt);
        return new Session(account, token, expiresAt);
    }

    private Account verifyCredentials(String email, String password) {
        var credentials = repository.findCredentials(normalizeEmail(email)).orElse(null);
        boolean correct = matchesPassword(password, credentials == null ? null : credentials.passwordHash());
        if (!correct || credentials == null) {
            throw invalidCredentials();
        }
        return credentials.account();
    }

    private boolean matchesPassword(String password, String passwordHash) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        boolean correct = passwords.matches(password, passwordHash == null ? unknownAccountHash : passwordHash);
        return passwordHash != null && correct;
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

    public record Session(Account account, String accessToken, Instant expiresAt) {}
}
