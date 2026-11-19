package com.remory.backend.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionRepository repository = mock(SessionRepository.class);
    private final SessionService service = new SessionService(accounts, repository);
    private final Account account = new Account("account-id", "person@example.com", true, false);
    private static final String REFRESH = "r".repeat(43);

    @BeforeEach
    void setup() {
        when(accounts.lockAccount(account.id())).thenReturn(true);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    }

    @Test
    void issuesIndependentOpaqueAccessAndRefreshTokensStoredOnlyAsHashes() {
        var session = service.issue(account);
        assertThat(session.accessToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(session.refreshToken());
        assertThat(session.refreshToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(session.expiresAt()).isBetween(Instant.now().plusSeconds(890), Instant.now().plusSeconds(910));
        assertThat(session.refreshExpiresAt()).isBetween(
                Instant.now().plus(Duration.ofDays(399)), Instant.now().plus(Duration.ofDays(400)));
        var family = ArgumentCaptor.forClass(String.class);
        verify(repository).insertFamily(family.capture(), eq(account.id()), isNull());
        verify(accounts).insertSession(account.id(), PasswordResetService.hash(session.accessToken()), session.expiresAt(), family.getValue());
        verify(repository).insertRefreshToken(PasswordResetService.hash(session.refreshToken()), family.getValue(), null);
    }

    @Test
    void refreshRotatesAndKeepsOriginalAccountFamily() {
        allowToken(null);
        var session = service.refresh(REFRESH);
        assertThat(session.account()).isEqualTo(account);
        assertThat(session.refreshToken()).isNotEqualTo(REFRESH);
        var historyExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markUsed(eq(PasswordResetService.hash(REFRESH)), any(), historyExpiry.capture());
        assertThat(historyExpiry.getValue()).isBetween(
                Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(30)));
        verify(repository).insertRefreshToken(PasswordResetService.hash(session.refreshToken()), "family-id", null);
        verify(repository, never()).insertFamily(any(), any(), any());
    }

    @Test
    void simultaneousReuseReturnsRetryWithoutRevokingOrIssuing() {
        allowToken(Instant.now());
        assertCode(() -> service.refresh(REFRESH), 409, "SESSION_REFRESH_RETRY");
        verify(repository, never()).deleteFamily(any());
        verify(repository, never()).insertRefreshToken(any(), any(), any());
    }

    @Test
    void olderReplayRevokesTheWholeFamily() {
        allowToken(Instant.now().minusSeconds(20));
        assertCode(() -> service.refresh(REFRESH), 401, "SESSION_EXPIRED");
        verify(repository).deleteFamily("family-id");
        verify(repository, never()).insertRefreshToken(any(), any(), any());
    }

    @Test
    void explicitlyExpiredFamilyCannotRenew() {
        allowToken(null);
        when(repository.lockFamily("family-id")).thenReturn(Optional.of(new SessionRepository.Family(Instant.now().minusSeconds(1))));
        assertCode(() -> service.refresh(REFRESH), 401, "SESSION_EXPIRED");
        verify(repository).deleteFamily("family-id");
    }

    @Test
    void unknownAndMalformedRefreshNeverIssueCredentials() {
        assertCode(() -> service.refresh("invalid"), 401, "SESSION_EXPIRED");
        assertCode(() -> service.refresh(REFRESH), 401, "SESSION_EXPIRED");
        verify(repository, never()).insertRefreshToken(any(), any(), any());
    }

    @Test
    void logoutRevokesCookieFamilyEvenWithoutValidAccess() {
        allowToken(null);
        service.logout(REFRESH, null);
        verify(repository).deleteFamily("family-id");
    }

    @Test
    void legacyMigrationRequiresAnUnconsumedLegacyAccessToken() {
        var hash = PasswordResetService.hash(REFRESH);
        when(accounts.findActiveSessionAccount(hash)).thenReturn(Optional.of(account));
        when(repository.consumeLegacyAccess(hash, account.id())).thenReturn(false);
        assertCode(() -> service.upgradeLegacy("Bearer " + REFRESH), 401, "SESSION_EXPIRED");
        verify(repository, never()).insertFamily(any(), any(), any());
    }

    private void allowToken(Instant usedAt) {
        var token = new SessionRepository.RefreshToken("family-id", account.id(), null, usedAt);
        when(repository.findToken(any())).thenReturn(Optional.of(token));
        when(repository.lockToken(any())).thenReturn(Optional.of(token));
        when(repository.lockFamily("family-id")).thenReturn(Optional.of(new SessionRepository.Family(null)));
    }

    private static void assertCode(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status);
            assertThat(error.getBody().getProperties()).containsEntry("code", code);
        });
    }
}
