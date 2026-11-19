package com.remory.backend.account;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {
    private AccountRepository accounts;
    private PasswordResetRepository resets;
    private PasswordResetDelivery delivery;
    private PasswordResetMail mail;
    private TaskExecutor executor;
    private PasswordResetService service;
    private static final String CHALLENGE = "c".repeat(43);
    private static final String GRANT = "g".repeat(43);
    private static final String CODE = "000123";

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        resets = mock(PasswordResetRepository.class);
        delivery = mock(PasswordResetDelivery.class);
        mail = mock(PasswordResetMail.class);
        executor = mock(TaskExecutor.class);
        service = new PasswordResetService(accounts, resets, delivery, mail, executor);
        when(mail.available()).thenReturn(true);
        when(resets.takeRequestSlot(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void requestCreatesOpaqueChallengeAndBoundCodeHashBeforeBackgroundLookup() {
        var response = service.request("  Person@EXAMPLE.COM  ", "ja", "127.0.0.1");
        assertThat(response.challengeId()).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.expiresAt()).isAfter(Instant.now().plusSeconds(590));
        assertThat(response.resendAt()).isBefore(response.expiresAt());
        verifyNoInteractions(accounts, delivery);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        var code = ArgumentCaptor.forClass(String.class);
        verify(delivery).deliver(eq("person@example.com"), eq("ja"), eq(response.challengeId()), code.capture());
        assertThat(code.getValue()).matches("[0-9]{6}");
        verify(resets).insertChallenge(eq(PasswordResetService.hash(response.challengeId())),
                eq(PasswordResetService.hash("person@example.com")),
                eq(PasswordResetService.codeHash(response.challengeId(), code.getValue())), eq(response.expiresAt()));
        verify(resets, times(3)).takeRequestSlot(any(), anyInt(), any());
    }

    @Test
    void unconfiguredMailDoesNotPretendToSend() {
        when(mail.available()).thenReturn(false);
        assertCode(() -> service.request("person@example.com", "ko", "127.0.0.1"), 503, "RESET_MAIL_UNAVAILABLE");
        verifyNoInteractions(resets, executor);
    }

    @Test
    void rateLimitStopsQueueingRegardlessOfAccountExistence() {
        when(resets.takeRequestSlot(any(), anyInt(), any())).thenReturn(false);
        assertCode(() -> service.request("person@example.com", "ko", "127.0.0.1"), 429, "RESET_RATE_LIMITED");
        verifyNoInteractions(accounts, delivery, executor);
    }

    @Test
    void queueFullRemovesOnlyNewChallenge() {
        doThrow(new TaskRejectedException("full")).when(executor).execute(any());
        assertCode(() -> service.request("person@example.com", "ko", "127.0.0.1"), 503, "RESET_MAIL_UNAVAILABLE");
        verify(resets).deleteChallenge(any());
    }

    @Test
    void correctCodePreservesLeadingZerosAndReturnsOneUseGrant() {
        challenge("account-id", 0, false, Instant.now().plusSeconds(600));
        var grant = service.verify(CHALLENGE, CODE);
        assertThat(grant.resetToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(grant.expiresAt()).isAfter(Instant.now().plusSeconds(290));
        verify(resets).incrementAttempts(PasswordResetService.hash(CHALLENGE));
        verify(resets).markVerified(eq(PasswordResetService.hash(CHALLENGE)),
                eq(PasswordResetService.hash(grant.resetToken())), eq(grant.expiresAt()));
        verify(resets, never()).changePassword(any(), any());
        verify(resets, never()).deleteSessions(any());
    }

    @Test
    void wrongCodeCountsAttemptWithoutIssuingGrant() {
        challenge("account-id", 0, false, Instant.now().plusSeconds(600));
        assertCode(() -> service.verify(CHALLENGE, "000124"), 400, "RESET_CODE_INVALID");
        verify(resets).incrementAttempts(any());
        verify(resets, never()).markVerified(any(), any(), any());
    }

    @Test
    void matchingCodeOnUnassociatedChallengeCannotVerifyUnknownAddress() {
        challenge(null, 0, false, Instant.now().plusSeconds(600));
        assertCode(() -> service.verify(CHALLENGE, CODE), 400, "RESET_CODE_INVALID");
        verify(resets).incrementAttempts(any());
        verify(resets, never()).markVerified(any(), any(), any());
    }

    @Test
    void fifthWrongAttemptLocksCodeAndFurtherCorrectCodeCannotVerify() {
        challenge("account-id", 4, false, Instant.now().plusSeconds(600));
        assertCode(() -> service.verify(CHALLENGE, "000124"), 429, "RESET_CODE_LOCKED");
        verify(resets).incrementAttempts(any());
        clearInvocations(resets);
        challenge("account-id", 5, false, Instant.now().plusSeconds(600));
        assertCode(() -> service.verify(CHALLENGE, CODE), 429, "RESET_CODE_LOCKED");
        verify(resets, never()).incrementAttempts(any());
    }

    @Test
    void expiredAndUsedCodesCannotVerify() {
        challenge("account-id", 0, false, Instant.now().minusSeconds(1));
        assertCode(() -> service.verify(CHALLENGE, CODE), 400, "RESET_CODE_EXPIRED");
        challenge("account-id", 1, true, Instant.now().plusSeconds(600));
        assertCode(() -> service.verify(CHALLENGE, CODE), 400, "RESET_CODE_INVALID");
        verify(resets, never()).markVerified(any(), any(), any());
    }

    @Test
    void resetStoresBcryptAndInvalidatesAllCodesAndSessionsUnderAccountLock() {
        allowConfirmation();
        service.confirm(GRANT, "new-password");
        var order = inOrder(accounts, resets);
        order.verify(accounts).lockAccount("account-id");
        order.verify(resets).consumeVerifiedToken(eq("account-id"), any());
        var encoded = ArgumentCaptor.forClass(String.class);
        order.verify(resets).changePassword(eq("account-id"), encoded.capture());
        order.verify(resets).deleteChallenges("account-id");
        order.verify(resets).deleteSessions("account-id");
        assertThat(new BCryptPasswordEncoder().matches("new-password", encoded.getValue())).isTrue();
    }

    @Test
    void expiredOrReplayedGrantCannotChangePassword() {
        assertCode(() -> service.confirm(GRANT, "new-password"), 400, "RESET_VERIFICATION_EXPIRED");
        verify(resets, never()).changePassword(any(), any());
        verifyNoInteractions(accounts);
    }

    @Test
    void grantExpiringWhileWaitingForLockCannotChangePassword() {
        allowConfirmation();
        when(resets.consumeVerifiedToken(any(), any())).thenReturn(false);
        assertCode(() -> service.confirm(GRANT, "new-password"), 400, "RESET_VERIFICATION_EXPIRED");
        verify(resets, never()).changePassword(any(), any());
    }

    @Test
    void missingPasswordRowNeverCreatesLocalPassword() {
        allowConfirmation();
        when(resets.changePassword(any(), any())).thenReturn(false);
        assertCode(() -> service.confirm(GRANT, "new-password"), 400, "RESET_VERIFICATION_EXPIRED");
        verify(resets, never()).deleteSessions(any());
    }

    @Test
    void rawCodeAndMultibytePasswordCannotBypassConfirmation() {
        assertCode(() -> service.confirm(CODE, "new-password"), 400, "RESET_VERIFICATION_EXPIRED");
        assertCode(() -> service.confirm(GRANT, "가".repeat(25)), 400, "RESET_PASSWORD_FORMAT");
        assertCode(() -> service.confirm(GRANT, "short"), 400, "RESET_PASSWORD_FORMAT");
        assertCode(() -> service.verify(CHALLENGE, "12345"), 400, "RESET_CODE_INVALID");
        verifyNoInteractions(accounts, resets);
    }

    @Test
    void loginRechecksLatestPasswordAfterLock() {
        var account = new Account("account-id", "person@example.com", true, false);
        var passwords = new BCryptPasswordEncoder(4);
        when(accounts.findCredentials(account.email())).thenReturn(Optional.of(
                new AccountRepository.Credentials(account, passwords.encode("old-password"))));
        when(accounts.lockAccount(account.id())).thenReturn(true);
        when(accounts.findPasswordHash(account.id())).thenReturn(Optional.of(passwords.encode("new-password")));
        var accountService = new AccountService(accounts);
        assertThatThrownBy(() -> accountService.login(account.email(), "old-password"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        verify(accounts, never()).insertSession(any(), any(), any());
        assertThat(accountService.login(account.email(), "new-password").account()).isEqualTo(account);
    }

    private void challenge(String accountId, int attempts, boolean verified, Instant expiresAt) {
        when(resets.lockChallenge(any())).thenReturn(Optional.of(new PasswordResetRepository.Challenge(
                accountId, PasswordResetService.codeHash(CHALLENGE, CODE), expiresAt, attempts, verified)));
    }

    private void allowConfirmation() {
        when(resets.findVerifiedAccount(any())).thenReturn(Optional.of("account-id"));
        when(accounts.lockAccount("account-id")).thenReturn(true);
        when(resets.consumeVerifiedToken(eq("account-id"), any())).thenReturn(true);
        when(resets.changePassword(eq("account-id"), any())).thenReturn(true);
    }

    private static void assertCode(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status);
            assertThat(error.getBody().getProperties()).containsEntry("code", code);
        });
    }
}
