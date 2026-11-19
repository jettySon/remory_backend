package com.remory.backend.account;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetDeliveryTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordResetRepository resets = mock(PasswordResetRepository.class);
    private final PasswordResetMail mail = mock(PasswordResetMail.class);
    private final PasswordResetDelivery delivery = new PasswordResetDelivery(accounts, resets, mail);
    private static final String CHALLENGE = "c".repeat(43);

    static Stream<Optional<Account>> nonLocalAccounts() {
        return Stream.of(Optional.empty(), Optional.of(new Account("google-id", "person@example.com", false, true)));
    }

    @ParameterizedTest
    @MethodSource("nonLocalAccounts")
    void unknownAndGoogleOnlyAddressesDoNotReceiveCodes(Optional<Account> account) {
        when(accounts.findByEmail("person@example.com")).thenReturn(account);
        delivery.deliver("person@example.com", "ko", CHALLENGE, "000123");
        verifyNoInteractions(resets, mail);
    }

    @Test
    void deliversCodeAndInvalidatesOlderChallengesAfterAccountLock() {
        allowAccount(false);
        delivery.deliver("person@example.com", "en", CHALLENGE, "000123");
        var order = inOrder(accounts, resets, mail);
        order.verify(accounts).lockAccount("account-id");
        order.verify(resets).isLatestChallenge(any(), any());
        order.verify(resets).attachAccount(PasswordResetService.hash(CHALLENGE), "account-id");
        order.verify(resets).deleteOtherChallenges("account-id", PasswordResetService.hash(CHALLENGE));
        order.verify(mail).send("person@example.com", "000123", "en");
    }

    @Test
    void linkedGoogleAccountWithLocalPasswordCanStillReset() {
        allowAccount(true);
        delivery.deliver("person@example.com", "ko", CHALLENGE, "000123");
        verify(mail).send("person@example.com", "000123", "ko");
    }

    @Test
    void staleQueuedRequestDoesNotInvalidateOrDeliver() {
        allowAccount(false);
        when(resets.isLatestChallenge(any(), any())).thenReturn(false);
        delivery.deliver("person@example.com", "ko", CHALLENGE, "000123");
        verify(resets, never()).attachAccount(any(), any());
        verify(resets, never()).deleteOtherChallenges(any(), any());
        verifyNoInteractions(mail);
    }

    @Test
    void expiredOrLockedChallengeDoesNotDeliver() {
        allowAccount(false);
        when(resets.attachAccount(any(), any())).thenReturn(false);
        delivery.deliver("person@example.com", "ko", CHALLENGE, "000123");
        verifyNoInteractions(mail);
    }

    @Test
    void mailFailureEscapesTransactionalMethodSoChangesRollBack() {
        allowAccount(false);
        doThrow(new MailSendException("no SMTP")).when(mail).send(any(), any(), any());
        assertThatThrownBy(() -> delivery.deliver("person@example.com", "ko", CHALLENGE, "000123"))
                .isInstanceOf(MailSendException.class);
    }

    private void allowAccount(boolean googleLinked) {
        when(accounts.findByEmail("person@example.com"))
                .thenReturn(Optional.of(new Account("account-id", "person@example.com", true, googleLinked)));
        when(accounts.lockAccount("account-id")).thenReturn(true);
        when(resets.isLatestChallenge(any(), any())).thenReturn(true);
        when(resets.attachAccount(any(), any())).thenReturn(true);
    }
}
