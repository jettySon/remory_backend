package com.remory.backend.account;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetRepository {
    private final JdbcClient jdbc;

    public PasswordResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean takeRequestSlot(byte[] key, int limit, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO password_reset_request_limits (key_hash, attempts, expires_at)
                VALUES (:key, 1, :expiresAt)
                ON DUPLICATE KEY UPDATE attempts = attempts + 1
                """)
                .param("key", key).param("expiresAt", Timestamp.from(expiresAt)).update();
        return jdbc.sql("SELECT attempts FROM password_reset_request_limits WHERE key_hash = :key")
                .param("key", key).query(Integer.class).single() <= limit;
    }

    void insertChallenge(byte[] challengeHash, byte[] emailHash, byte[] codeHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO password_reset_challenges (challenge_hash, email_hash, code_hash, expires_at)
                VALUES (:challenge, :email, :code, :expiresAt)
                """)
                .param("challenge", challengeHash).param("email", emailHash).param("code", codeHash)
                .param("expiresAt", Timestamp.from(expiresAt)).update();
    }

    boolean isLatestChallenge(byte[] emailHash, byte[] challengeHash) {
        return jdbc.sql("""
                SELECT challenge_hash FROM password_reset_challenges
                WHERE email_hash = :email ORDER BY created_at DESC, challenge_hash DESC LIMIT 1
                """)
                .param("email", emailHash).query((row, index) -> MessageDigest.isEqual(row.getBytes(1), challengeHash))
                .optional().orElse(false);
    }

    boolean attachAccount(byte[] challengeHash, String accountId) {
        return jdbc.sql("""
                UPDATE password_reset_challenges SET account_id = :account
                WHERE challenge_hash = :challenge AND expires_at > CURRENT_TIMESTAMP(6)
                  AND attempts < :maximum AND verified_token_hash IS NULL
                """)
                .param("account", accountId).param("challenge", challengeHash)
                .param("maximum", PasswordResetPolicy.MAX_ATTEMPTS).update() == 1;
    }

    Optional<Challenge> lockChallenge(byte[] challengeHash) {
        return jdbc.sql("""
                SELECT account_id, code_hash, expires_at, attempts, verified_token_hash IS NOT NULL AS verified
                FROM password_reset_challenges WHERE challenge_hash = :challenge FOR UPDATE
                """)
                .param("challenge", challengeHash)
                .query((row, index) -> new Challenge(row.getString("account_id"), row.getBytes("code_hash"),
                        row.getTimestamp("expires_at").toInstant(), row.getInt("attempts"), row.getBoolean("verified")))
                .optional();
    }

    void incrementAttempts(byte[] challengeHash) {
        jdbc.sql("UPDATE password_reset_challenges SET attempts = attempts + 1 WHERE challenge_hash = :challenge")
                .param("challenge", challengeHash).update();
    }

    void markVerified(byte[] challengeHash, byte[] tokenHash, Instant expiresAt) {
        jdbc.sql("""
                UPDATE password_reset_challenges SET verified_token_hash = :token, verified_expires_at = :expiresAt
                WHERE challenge_hash = :challenge
                """)
                .param("token", tokenHash).param("expiresAt", Timestamp.from(expiresAt))
                .param("challenge", challengeHash).update();
    }

    Optional<String> findVerifiedAccount(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT account_id FROM password_reset_challenges
                WHERE verified_token_hash = :token AND verified_expires_at > CURRENT_TIMESTAMP(6)
                  AND account_id IS NOT NULL
                """)
                .param("token", tokenHash).query(String.class).optional();
    }

    boolean consumeVerifiedToken(String accountId, byte[] tokenHash) {
        return jdbc.sql("""
                DELETE FROM password_reset_challenges
                WHERE verified_token_hash = :token AND account_id = :account
                  AND verified_expires_at > CURRENT_TIMESTAMP(6)
                """)
                .param("token", tokenHash).param("account", accountId).update() == 1;
    }

    void deleteChallenge(byte[] challengeHash) {
        jdbc.sql("DELETE FROM password_reset_challenges WHERE challenge_hash = :challenge")
                .param("challenge", challengeHash).update();
    }

    void deleteOtherChallenges(String accountId, byte[] currentHash) {
        jdbc.sql("DELETE FROM password_reset_challenges WHERE account_id = :account AND challenge_hash <> :current")
                .param("account", accountId).param("current", currentHash).update();
    }

    void deleteChallenges(String accountId) {
        jdbc.sql("DELETE FROM password_reset_challenges WHERE account_id = :account")
                .param("account", accountId).update();
    }

    boolean changePassword(String accountId, String passwordHash) {
        return jdbc.sql("UPDATE account_passwords SET password_hash = :hash WHERE account_id = :account")
                .param("hash", passwordHash).param("account", accountId).update() == 1;
    }

    void deleteSessions(String accountId) {
        jdbc.sql("DELETE FROM account_sessions WHERE account_id = :account")
                .param("account", accountId).update();
    }

    void deleteExpired() {
        jdbc.sql("""
                DELETE FROM password_reset_challenges
                WHERE (verified_token_hash IS NULL AND expires_at <= CURRENT_TIMESTAMP(6))
                   OR (verified_token_hash IS NOT NULL AND verified_expires_at <= CURRENT_TIMESTAMP(6))
                """).update();
        jdbc.sql("DELETE FROM password_reset_request_limits WHERE expires_at <= CURRENT_TIMESTAMP(6)").update();
    }

    record Challenge(String accountId, byte[] codeHash, Instant expiresAt, int attempts, boolean verified) {}
}
