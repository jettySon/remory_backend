package com.remory.backend.account;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final AccountRepository repository;
    private final SessionService sessions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String unknownAccountHash = passwords.encode("unregistered-account");
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository repository, SessionService sessions) {
        this.repository = repository;
        this.sessions = sessions;
    }

    @Transactional
    public Session signup(String email, String password) {
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상, UTF-8 기준 72바이트 이하여야 해요.");
        }

        var account = new Account(UUID.randomUUID().toString(), normalizeEmail(email), true, false);
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
        return issueSession(account);
    }

    @Transactional
    public void deleteSignedInAccount(String authorization, String password) {
        Account account = authenticate(authorization);
        if (!repository.lockAccount(account.id())) throw invalidCredentials();
        authenticate(authorization);
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

    @Scheduled(fixedDelayString = "PT6H")
    void removeExpiredSessions() {
        repository.deleteExpiredSessions();
        repository.deleteExpiredGoogleChallenges();
    }

    @Transactional
    public String createGoogleChallenge(String browser, String purpose) {
        String nonce = randomToken();
        repository.insertGoogleChallenge(hash(nonce), hash(browser), purpose, Instant.now().plus(Duration.ofMinutes(5)));
        return nonce;
    }

    @Transactional
    public Session loginWithGoogle(GoogleIdentityVerifier.Identity identity, String browser, String password) {
        // The one-use nonce binds Google's response to this browser and this login attempt.
        consumeGoogleChallenge(identity, browser, "login");
        Account account = repository.findGoogleAccount(identity.subject()).orElse(null);
        if (account != null) {
            if (!repository.lockAccount(account.id())) throw invalidCredentials();
            return issueSession(account);
        }

        String email = normalizeEmail(identity.email());
        account = repository.findByEmail(email).orElse(null);
        if (account != null) {
            if (!repository.lockAccount(account.id())) throw invalidCredentials();
            // Email matching alone cannot link an existing local account.
            if (account.googleLinked() || !account.hasPassword()) {
                throw googleError(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_CONFLICT");
            }
            if (password == null || password.isBlank()) {
                throw googleError(HttpStatus.CONFLICT, "GOOGLE_LINK_REQUIRED");
            }
            if (!matchesPassword(password, repository.findPasswordHash(account.id()).orElse(null))) {
                throw googleError(HttpStatus.FORBIDDEN, "GOOGLE_LINK_PASSWORD");
            }
        } else {
            account = new Account(UUID.randomUUID().toString(), email, false, true);
        }

        try {
            if (!account.hasPassword()) repository.insertAccount(account);
            repository.insertGoogleIdentity(account.id(), identity.subject());
        } catch (DuplicateKeyException error) {
            // Roll back both the new account and nonce consumption if another login won the race.
            throw googleError(HttpStatus.CONFLICT, "GOOGLE_RETRY");
        }
        return issueSession(new Account(account.id(), account.email(), account.hasPassword(), true));
    }

    @Transactional
    public Account deleteWithGoogle(String authorization, GoogleIdentityVerifier.Identity identity, String browser) {
        Account current = authorization == null ? null : authenticate(authorization);
        consumeGoogleChallenge(identity, browser, "delete");
        Account googleAccount = repository.findGoogleAccount(identity.subject())
                .orElseThrow(() -> googleError(HttpStatus.NOT_FOUND, "GOOGLE_ACCOUNT_NOT_FOUND"));
        if (current != null && !current.id().equals(googleAccount.id())) {
            throw googleError(HttpStatus.FORBIDDEN, "GOOGLE_ACCOUNT_MISMATCH");
        }
        if (!repository.lockAccount(googleAccount.id())) {
            throw googleError(HttpStatus.NOT_FOUND, "GOOGLE_ACCOUNT_NOT_FOUND");
        }
        repository.deleteAccount(googleAccount.id());
        return googleAccount;
    }

    private void consumeGoogleChallenge(GoogleIdentityVerifier.Identity identity, String browser, String purpose) {
        if (browser == null || !BEARER_TOKEN.matcher(browser).matches()
                || !repository.consumeGoogleChallenge(hash(identity.nonce()), hash(browser), purpose)) {
            throw googleError(HttpStatus.UNAUTHORIZED, "GOOGLE_RESTART");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static ResponseStatusException googleError(HttpStatus status, String code) {
        var error = new ResponseStatusException(status, code);
        error.getBody().setProperty("code", code);
        return error;
    }

    private Session issueSession(Account account) {
        return sessions.issue(account);
    }

    private Account verifyCredentials(String email, String password) {
        var credentials = repository.findCredentials(normalizeEmail(email)).orElse(null);
        // 비밀번호 재설정과 같은 계정 잠금을 사용하고, 잠금 후 최신 비밀번호를 확인한다.
        boolean locked = credentials != null && repository.lockAccount(credentials.account().id());
        String currentHash = locked ? repository.findPasswordHash(credentials.account().id()).orElse(null) : null;
        boolean correct = matchesPassword(password, currentHash);
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

    static byte[] authorizationHash(String authorization) {
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

    public record Session(Account account, String accessToken, Instant expiresAt,
            @JsonIgnore String refreshToken, @JsonIgnore Instant refreshExpiresAt) {
        public Session(Account account, String accessToken, Instant expiresAt) {
            this(account, accessToken, expiresAt, null, null);
        }
    }
}
