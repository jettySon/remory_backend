package com.remory.backend.account;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {
    static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    // Browser cookies have a finite retention limit; renew this on every refresh.
    static final Duration REFRESH_COOKIE_LIFETIME = Duration.ofDays(400);
    private static final Duration USED_TOKEN_LIFETIME = Duration.ofDays(30);
    private static final Duration CONCURRENT_REFRESH_WINDOW = Duration.ofSeconds(10);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final AccountRepository accounts;
    private final SessionRepository repository;

    public SessionService(AccountRepository accounts, SessionRepository repository) {
        this.accounts = accounts;
        this.repository = repository;
    }

    @Transactional
    public AccountService.Session issue(Account account) {
        String familyId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        // NULL expiry keeps the device signed in until explicit or security-related revocation.
        repository.insertFamily(familyId, account.id(), null);
        return tokens(account, familyId, now);
    }

    @Transactional(noRollbackFor = Rejected.class)
    public AccountService.Session refresh(String rawToken) {
        if (!valid(rawToken)) throw expired();
        byte[] hash = PasswordResetService.hash(rawToken);
        var initial = repository.findToken(hash).orElseThrow(SessionService::expired);
        // All auth operations lock the account first, then the device family and refresh row.
        if (!accounts.lockAccount(initial.accountId())) throw expired();
        var family = repository.lockFamily(initial.familyId()).orElseThrow(SessionService::expired);
        var token = repository.lockToken(hash).orElseThrow(SessionService::expired);
        Instant now = Instant.now();
        if ((family.expiresAt() != null && !family.expiresAt().isAfter(now))
                || (token.expiresAt() != null && !token.expiresAt().isAfter(now))) {
            repository.deleteFamily(token.familyId());
            throw expired();
        }
        if (token.usedAt() != null) {
            // A simultaneous request gets no credentials and can retry with the browser's newest cookie.
            // Older replays revoke the whole family. Rejected MUST commit that revocation.
            if (token.usedAt().plus(CONCURRENT_REFRESH_WINDOW).isAfter(now)) {
                throw new Rejected(HttpStatus.CONFLICT, "SESSION_REFRESH_RETRY");
            }
            repository.deleteFamily(token.familyId());
            throw expired();
        }
        Account account = accounts.findById(token.accountId()).orElseThrow(SessionService::expired);
        // Only consumed-token replay history expires; the current credential has no idle cutoff.
        repository.markUsed(hash, now, now.plus(USED_TOKEN_LIFETIME));
        return tokens(account, token.familyId(), now);
    }

    // Background reminders only check the current session. They never rotate or consume its cookie.
    String accountForReminder(String rawToken) {
        var accountId = valid(rawToken)
                ? repository.findActiveRefreshAccount(PasswordResetService.hash(rawToken), Instant.now())
                : java.util.Optional.<String>empty();
        return accountId.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED"));
    }

    @Transactional
    public AccountService.Session upgradeLegacy(String authorization) {
        byte[] hash = AccountService.authorizationHash(authorization);
        Account account = accounts.findActiveSessionAccount(hash).orElseThrow(SessionService::expired);
        if (!accounts.lockAccount(account.id()) || !repository.consumeLegacyAccess(hash, account.id())) throw expired();
        return issue(account);
    }

    @Transactional
    public void logout(String rawRefresh, String authorization) {
        if (valid(rawRefresh)) {
            var token = repository.findToken(PasswordResetService.hash(rawRefresh)).orElse(null);
            if (token != null) {
                if (accounts.lockAccount(token.accountId())) repository.deleteFamily(token.familyId());
                return;
            }
        }
        // Logout is idempotent and can also revoke an expired access token or a pre-upgrade session.
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || !valid(authorization.substring(7))) return;
        byte[] hash = AccountService.authorizationHash(authorization);
        var grant = repository.findAccessGrant(hash).orElse(null);
        if (grant == null || !accounts.lockAccount(grant.accountId())) return;
        if (grant.familyId() == null) accounts.deleteSession(hash);
        else repository.deleteFamily(grant.familyId());
    }

    private AccountService.Session tokens(Account account, String familyId, Instant now) {
        String access = randomToken();
        String refresh = randomToken();
        Instant accessExpiry = now.plus(ACCESS_LIFETIME);
        accounts.insertSession(account.id(), PasswordResetService.hash(access), accessExpiry, familyId);
        repository.insertRefreshToken(PasswordResetService.hash(refresh), familyId, null);
        return new AccountService.Session(account, access, accessExpiry, refresh, now.plus(REFRESH_COOKIE_LIFETIME));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean valid(String token) { return token != null && TOKEN.matcher(token).matches(); }
    private static Rejected expired() { return new Rejected(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED"); }

    @Scheduled(fixedDelayString = "PT6H")
    void removeExpired() { repository.deleteExpired(); }

    static final class Rejected extends ResponseStatusException {
        Rejected(HttpStatus status, String code) {
            super(status, code);
            getBody().setProperty("code", code);
        }
    }
}
