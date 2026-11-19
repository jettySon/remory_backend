package com.remory.backend.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void insertFamily(String id, String accountId, Instant expiresAt) {
        jdbc.sql("INSERT INTO account_refresh_families (id, account_id, expires_at) VALUES (:id, :account, :expiry)")
                .param("id", id).param("account", accountId).param("expiry", expiresAt == null ? null : Timestamp.from(expiresAt), java.sql.Types.TIMESTAMP).update();
    }

    void insertRefreshToken(byte[] hash, String familyId, Instant expiresAt) {
        jdbc.sql("INSERT INTO account_refresh_tokens (token_hash, family_id, expires_at) VALUES (:hash, :family, :expiry)")
                .param("hash", hash).param("family", familyId).param("expiry", expiresAt == null ? null : Timestamp.from(expiresAt), java.sql.Types.TIMESTAMP).update();
    }

    Optional<RefreshToken> findToken(byte[] hash) {
        return token(hash, false);
    }

    Optional<RefreshToken> lockToken(byte[] hash) {
        return token(hash, true);
    }

    private Optional<RefreshToken> token(byte[] hash, boolean lock) {
        return jdbc.sql("""
                SELECT t.family_id, f.account_id, t.expires_at, t.used_at
                FROM account_refresh_tokens t JOIN account_refresh_families f ON f.id = t.family_id
                WHERE t.token_hash = :hash
                """ + (lock ? " FOR UPDATE" : ""))
                .param("hash", hash).query((row, index) -> new RefreshToken(row.getString("family_id"),
                        row.getString("account_id"),
                        row.getTimestamp("expires_at") == null ? null : row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("used_at") == null ? null : row.getTimestamp("used_at").toInstant())).optional();
    }

    Optional<Family> lockFamily(String familyId) {
        // A present row with NULL expiry is an active session, not a missing family.
        return jdbc.sql("SELECT expires_at FROM account_refresh_families WHERE id = :id FOR UPDATE")
                .param("id", familyId).query((row, index) -> new Family(
                        row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant())).optional();
    }

    void markUsed(byte[] oldHash, Instant now, Instant historyExpiresAt) {
        jdbc.sql("UPDATE account_refresh_tokens SET used_at = :now, expires_at = :expiry WHERE token_hash = :hash")
                .param("now", Timestamp.from(now)).param("expiry", Timestamp.from(historyExpiresAt))
                .param("hash", oldHash).update();
    }

    void deleteFamily(String familyId) {
        // The foreign key also revokes all access tokens issued to this device session.
        jdbc.sql("DELETE FROM account_refresh_families WHERE id = :id").param("id", familyId).update();
    }

    Optional<AccessGrant> findAccessGrant(byte[] hash) {
        return jdbc.sql("SELECT account_id, family_id FROM account_sessions WHERE token_hash = :hash")
                .param("hash", hash).query((row, index) -> new AccessGrant(row.getString(1), row.getString(2))).optional();
    }

    boolean consumeLegacyAccess(byte[] hash, String accountId) {
        return jdbc.sql("""
                DELETE FROM account_sessions WHERE token_hash = :hash AND account_id = :account
                AND family_id IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                """).param("hash", hash).param("account", accountId).update() == 1;
    }

    void deleteExpired() {
        // NULL-expiry device sessions and unused tokens survive cleanup regardless of inactivity.
        jdbc.sql("DELETE FROM account_refresh_families WHERE expires_at <= CURRENT_TIMESTAMP(6)").update();
        jdbc.sql("DELETE FROM account_refresh_tokens WHERE expires_at <= CURRENT_TIMESTAMP(6)").update();
    }

    record Family(Instant expiresAt) {}
    record RefreshToken(String familyId, String accountId, Instant expiresAt, Instant usedAt) {}
    record AccessGrant(String accountId, String familyId) {}
}
