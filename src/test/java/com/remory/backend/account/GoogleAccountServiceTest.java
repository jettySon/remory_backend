package com.remory.backend.account;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleAccountServiceTest {

    private AccountRepository repository;
    private AccountService service;
    private SessionService sessions;
    private final GoogleIdentityVerifier.Identity identity =
            new GoogleIdentityVerifier.Identity("google-subject", "person@example.com", "n".repeat(43));
    private static final String BROWSER = "b".repeat(43);

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        sessions = mock(SessionService.class);
        when(sessions.issue(any())).thenAnswer(call -> new AccountService.Session(call.getArgument(0), "t".repeat(43), java.time.Instant.now().plusSeconds(900)));
        service = new AccountService(repository, sessions);
    }

    private void allowLogin() {
        when(repository.consumeGoogleChallenge(any(), any(), eq("login"))).thenReturn(true);
    }

    @Test
    void firstLoginCreatesGoogleIdentityAndNormalSession() {
        allowLogin();
        var session = service.loginWithGoogle(identity, BROWSER, null);
        assertThat(session.account().hasPassword()).isFalse();
        assertThat(session.account().googleLinked()).isTrue();
        assertThat(session.accessToken()).hasSize(43);
        verify(repository).insertGoogleIdentity(session.account().id(), identity.subject());
        verify(repository, never()).insertPassword(any(), any());
    }

    @Test
    void returningUserUsesStableSubjectEvenIfGoogleEmailChanged() {
        allowLogin();
        var account = new Account("existing-id", "previous@example.com", false, true);
        when(repository.findGoogleAccount(identity.subject())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        assertThat(service.loginWithGoogle(identity, BROWSER, null).account()).isEqualTo(account);
        verify(repository, never()).insertAccount(any());
    }

    @Test
    void existingEmailRequiresExplicitPasswordConfirmation() {
        allowLogin();
        var account = new Account("local-id", identity.email(), true, false);
        when(repository.findByEmail(identity.email())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        assertCode(() -> service.loginWithGoogle(identity, BROWSER, null), 409, "GOOGLE_LINK_REQUIRED");
        verify(repository, never()).insertGoogleIdentity(any(), any());
        verify(sessions, never()).issue(any());
    }

    @Test
    void correctPasswordLinksExistingAccountWithoutCreatingNewAccount() {
        allowLogin();
        var account = new Account("local-id", identity.email(), true, false);
        when(repository.findByEmail(identity.email())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        when(repository.findPasswordHash(account.id()))
                .thenReturn(Optional.of(new BCryptPasswordEncoder().encode("correct-password")));
        var session = service.loginWithGoogle(identity, BROWSER, "correct-password");
        assertThat(session.account().id()).isEqualTo(account.id());
        assertThat(session.account().hasPassword()).isTrue();
        assertThat(session.account().googleLinked()).isTrue();
        verify(repository, never()).insertAccount(any());
    }

    @Test
    void wrongPasswordDoesNotLinkOrIssueSession() {
        allowLogin();
        var account = new Account("local-id", identity.email(), true, false);
        when(repository.findByEmail(identity.email())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        when(repository.findPasswordHash(account.id()))
                .thenReturn(Optional.of(new BCryptPasswordEncoder().encode("correct-password")));
        assertCode(() -> service.loginWithGoogle(identity, BROWSER, "wrong-password"), 403, "GOOGLE_LINK_PASSWORD");
        verify(repository, never()).insertGoogleIdentity(any(), any());
        verify(sessions, never()).issue(any());
    }

    @Test
    void anotherGoogleIdentityCannotTakeOverAnExistingEmail() {
        allowLogin();
        var account = new Account("other-id", identity.email(), false, true);
        when(repository.findByEmail(identity.email())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        assertCode(() -> service.loginWithGoogle(identity, BROWSER, null), 409, "GOOGLE_ACCOUNT_CONFLICT");
        verify(repository, never()).insertGoogleIdentity(any(), any());
    }

    @Test
    void replayOrWrongBrowserFailsBeforeAccountLookup() {
        assertCode(() -> service.loginWithGoogle(identity, BROWSER, null), 401, "GOOGLE_RESTART");
        verify(repository, never()).findGoogleAccount(any());
        verify(sessions, never()).issue(any());
    }

    @Test
    void concurrentIdentityInsertFailsWithoutIssuingSession() {
        allowLogin();
        doThrow(new DuplicateKeyException("identity already exists")).when(repository).insertGoogleIdentity(any(), any());
        assertCode(() -> service.loginWithGoogle(identity, BROWSER, null), 409, "GOOGLE_RETRY");
        verify(sessions, never()).issue(any());
    }

    @Test
    void publicGoogleDeletionUsesLinkedSubjectAndDeletePurpose() {
        var account = new Account("google-id", identity.email(), false, true);
        when(repository.consumeGoogleChallenge(any(), any(), eq("delete"))).thenReturn(true);
        when(repository.findGoogleAccount(identity.subject())).thenReturn(Optional.of(account));
        when(repository.lockAccount(account.id())).thenReturn(true);
        assertThat(service.deleteWithGoogle(null, identity, BROWSER)).isEqualTo(account);
        verify(repository).deleteAccount(account.id());
    }

    @Test
    void signedInUserCannotDeleteAnotherGoogleAccount() {
        var current = new Account("current-id", "current@example.com", true, false);
        var other = new Account("other-id", identity.email(), false, true);
        when(repository.findActiveSessionAccount(any())).thenReturn(Optional.of(current));
        when(repository.consumeGoogleChallenge(any(), any(), eq("delete"))).thenReturn(true);
        when(repository.findGoogleAccount(identity.subject())).thenReturn(Optional.of(other));
        assertCode(() -> service.deleteWithGoogle("Bearer " + "t".repeat(43), identity, BROWSER),
                403, "GOOGLE_ACCOUNT_MISMATCH");
        verify(repository, never()).deleteAccount(any());
    }

    @Test
    void loginNonceCannotAuthorizeDeletion() {
        assertCode(() -> service.deleteWithGoogle(null, identity, BROWSER), 401, "GOOGLE_RESTART");
        verify(repository, never()).deleteAccount(any());
    }

    private static void assertCode(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status);
            assertThat(error.getBody().getProperties()).containsEntry("code", code);
        });
    }
}
