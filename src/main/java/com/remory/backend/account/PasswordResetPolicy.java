package com.remory.backend.account;

import java.time.Duration;

final class PasswordResetPolicy {
    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    static final Duration VERIFIED_LIFETIME = Duration.ofMinutes(5);
    static final Duration RESEND_DELAY = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;

    private PasswordResetPolicy() {}
}
