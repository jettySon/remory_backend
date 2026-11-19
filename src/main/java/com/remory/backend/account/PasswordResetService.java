package com.remory.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final PasswordResetDelivery delivery;
    private final PasswordResetMail mail;
    private final TaskExecutor executor;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(AccountRepository accounts, PasswordResetRepository resets,
            PasswordResetDelivery delivery, PasswordResetMail mail,
            @Qualifier("passwordResetExecutor") TaskExecutor executor) {
        this.accounts = accounts;
        this.resets = resets;
        this.delivery = delivery;
        this.mail = mail;
        this.executor = executor;
    }

    public RequestChallenge request(String email, String locale, String remoteAddress) {
        if (!mail.available()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "RESET_MAIL_UNAVAILABLE");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!takeSlot("ip:" + remoteAddress, 20, 3600)
                || !takeSlot("email-hour:" + normalized, 3, 3600)
                || !takeSlot("email-minute:" + normalized, 1, 60)) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "RESET_RATE_LIMITED");
        }
        String challengeId = randomToken();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(PasswordResetPolicy.CODE_LIFETIME);
        // Even unknown addresses receive the same opaque challenge and response.
        // Hashing the code with the secret random challenge prevents a DB dump from brute-forcing six digits alone.
        resets.insertChallenge(hash(challengeId), hash(normalized), codeHash(challengeId, code), expiresAt);
        try {
            executor.execute(() -> {
                try {
                    delivery.deliver(normalized, locale, challengeId, code);
                } catch (RuntimeException failure) {
                    // SMTP errors can include personal information. Never log their message or codes/tokens.
                    log.warn("Password reset delivery failed ({})", failure.getClass().getSimpleName());
                }
            });
        } catch (TaskRejectedException rejected) {
            resets.deleteChallenge(hash(challengeId));
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "RESET_MAIL_UNAVAILABLE");
        }
        return new RequestChallenge(challengeId, expiresAt, now.plus(PasswordResetPolicy.RESEND_DELAY));
    }

    @Transactional(noRollbackFor = CodeRejectedException.class)
    public Verification verify(String challengeId, String code) {
        if (challengeId == null || !TOKEN.matcher(challengeId).matches()
                || code == null || !CODE.matcher(code).matches()) throw rejected("RESET_CODE_INVALID", HttpStatus.BAD_REQUEST);
        byte[] challengeHash = hash(challengeId);
        var challenge = resets.lockChallenge(challengeHash)
                .orElseThrow(() -> rejected("RESET_CODE_INVALID", HttpStatus.BAD_REQUEST));
        if (challenge.verified()) throw rejected("RESET_CODE_INVALID", HttpStatus.BAD_REQUEST);
        if (!challenge.expiresAt().isAfter(Instant.now())) throw rejected("RESET_CODE_EXPIRED", HttpStatus.BAD_REQUEST);
        if (challenge.attempts() >= PasswordResetPolicy.MAX_ATTEMPTS) throw rejected("RESET_CODE_LOCKED", HttpStatus.TOO_MANY_REQUESTS);

        // A row lock serializes guesses. Rejected guesses MUST commit this increment;
        // rolling the transaction back with the validation error would allow unlimited guessing.
        resets.incrementAttempts(challengeHash);
        boolean correct = MessageDigest.isEqual(codeHash(challengeId, code), challenge.codeHash());
        if (!correct || challenge.accountId() == null) {
            if (challenge.attempts() + 1 >= PasswordResetPolicy.MAX_ATTEMPTS) throw rejected("RESET_CODE_LOCKED", HttpStatus.TOO_MANY_REQUESTS);
            throw rejected("RESET_CODE_INVALID", HttpStatus.BAD_REQUEST);
        }
        String token = randomToken();
        Instant expiresAt = Instant.now().plus(PasswordResetPolicy.VERIFIED_LIFETIME);
        // This grant permits only a password change, never a normal login. Codes cannot be reused to get another grant.
        resets.markVerified(challengeHash, hash(token), expiresAt);
        return new Verification(token, expiresAt);
    }

    @Transactional
    public void confirm(String token, String password) {
        if (token == null || !TOKEN.matcher(token).matches()) throw error(HttpStatus.BAD_REQUEST, "RESET_VERIFICATION_EXPIRED");
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw error(HttpStatus.BAD_REQUEST, "RESET_PASSWORD_FORMAT");
        }
        byte[] tokenHash = hash(token);
        String accountId = resets.findVerifiedAccount(tokenHash)
                .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "RESET_VERIFICATION_EXPIRED"));
        if (!accounts.lockAccount(accountId) || !resets.consumeVerifiedToken(accountId, tokenHash)) {
            throw error(HttpStatus.BAD_REQUEST, "RESET_VERIFICATION_EXPIRED");
        }
        if (!resets.changePassword(accountId, passwords.encode(password))) {
            throw error(HttpStatus.BAD_REQUEST, "RESET_VERIFICATION_EXPIRED");
        }
        resets.deleteChallenges(accountId);
        resets.deleteSessions(accountId);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean takeSlot(String key, int limit, int seconds) {
        long bucket = Instant.now().getEpochSecond() / seconds;
        return resets.takeRequestSlot(hash(key + ":" + bucket), limit, Instant.ofEpochSecond((bucket + 1) * seconds));
    }

    @Scheduled(fixedDelayString = "PT6H")
    void removeExpired() {
        resets.deleteExpired();
    }

    static byte[] codeHash(String challengeId, String code) {
        return hash(challengeId + ":" + code);
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static CodeRejectedException rejected(String code, HttpStatus status) {
        return new CodeRejectedException(status, code);
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        var error = new ResponseStatusException(status, code);
        error.getBody().setProperty("code", code);
        return error;
    }

    static final class CodeRejectedException extends ResponseStatusException {
        CodeRejectedException(HttpStatus status, String code) {
            super(status, code);
            getBody().setProperty("code", code);
        }
    }

    public record RequestChallenge(String challengeId, Instant expiresAt, Instant resendAt) {}
    public record Verification(String resetToken, Instant expiresAt) {}
}
