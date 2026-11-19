package com.remory.backend.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetDelivery {
    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final PasswordResetMail mail;

    public PasswordResetDelivery(AccountRepository accounts, PasswordResetRepository resets, PasswordResetMail mail) {
        this.accounts = accounts;
        this.resets = resets;
        this.mail = mail;
    }

    @Transactional
    public void deliver(String email, String locale, String challengeId, String code) {
        var account = accounts.findByEmail(email).orElse(null);
        if (account == null || !account.hasPassword() || !accounts.lockAccount(account.id())) return;
        byte[] challengeHash = PasswordResetService.hash(challengeId);
        // Resends can queue out of order. Only the latest request may deliver a new code.
        if (!resets.isLatestChallenge(PasswordResetService.hash(email), challengeHash)) return;
        if (!resets.attachAccount(challengeHash, account.id())) return;
        resets.deleteOtherChallenges(account.id(), challengeHash);
        // SMTP failure rolls back the association and restores the previous usable code.
        mail.send(account.email(), code, locale);
    }
}
