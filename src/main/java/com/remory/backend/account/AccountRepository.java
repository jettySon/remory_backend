package com.remory.backend.account;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String ACCOUNT_COLUMNS = """
            a.id, a.email,
            EXISTS (SELECT 1 FROM account_passwords p WHERE p.account_id = a.id) AS has_password,
            EXISTS (SELECT 1 FROM account_identities i WHERE i.account_id = a.id AND i.provider = 'google') AS google_linked
            """;

    private static Account mapAccount(ResultSet row, int index) throws SQLException {
        return new Account(row.getString("id"), row.getString("email"),
                row.getBoolean("has_password"), row.getBoolean("google_linked"));
    }

    private final JdbcTemplate database;
    private final JdbcClient jdbc;

    public AccountRepository(JdbcTemplate database, JdbcClient jdbc) {
        this.database = database;
        this.jdbc = jdbc;
    }

    Optional<Account> findById(String accountId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM accounts a WHERE a.id = :id")
                .param("id", accountId).query(AccountRepository::mapAccount).optional();
    }

    void insertAccount(Account account) {
        database.update("INSERT INTO accounts (id, email) VALUES (?, ?)", account.id(), account.email());
    }

    void insertPassword(String accountId, String passwordHash) {
        database.update("INSERT INTO account_passwords (account_id, password_hash) VALUES (?, ?)",
                accountId, passwordHash);
    }

    Optional<Credentials> findCredentials(String email) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + ", p.password_hash " + """
                FROM accounts a JOIN account_passwords p ON p.account_id = a.id
                WHERE a.email = :email
                """)
                .param("email", email)
                .query((row, index) -> new Credentials(
                        mapAccount(row, index), row.getString("password_hash")))
                .optional();
    }

    Optional<String> findPasswordHash(String accountId) {
        return jdbc.sql("SELECT password_hash FROM account_passwords WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(String.class)
                .optional();
    }

    boolean lockAccount(String accountId) {
        return jdbc.sql("SELECT id FROM accounts WHERE id = :accountId FOR UPDATE")
                .param("accountId", accountId)
                .query(String.class)
                .optional()
                .isPresent();
    }

    void deleteAccount(String accountId) {
        // 계정에 연결된 세션, 학습 데이터와 이관 전 스냅샷은 외래 키로 함께 삭제된다.
        database.update("DELETE FROM accounts WHERE id = ?", accountId);
    }

    Optional<Account> findActiveSessionAccount(byte[] tokenHash) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + """
                 FROM account_sessions s JOIN accounts a ON a.id = s.account_id
                WHERE s.token_hash = :tokenHash AND s.expires_at > CURRENT_TIMESTAMP(6)
                  AND (s.family_id IS NULL OR EXISTS (
                    SELECT 1 FROM account_refresh_families f WHERE f.id = s.family_id
                    AND (f.expires_at IS NULL OR f.expires_at > CURRENT_TIMESTAMP(6))))
                """)
                .param("tokenHash", tokenHash)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    void insertSession(String accountId, byte[] tokenHash, Instant expiresAt) {
        database.update("""
                INSERT INTO account_sessions (token_hash, account_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, accountId, Timestamp.from(expiresAt));
    }

    void insertSession(String accountId, byte[] tokenHash, Instant expiresAt, String familyId) {
        database.update("""
                INSERT INTO account_sessions (token_hash, account_id, expires_at, family_id)
                VALUES (?, ?, ?, ?)
                """, tokenHash, accountId, Timestamp.from(expiresAt), familyId);
    }

    void deleteSession(byte[] tokenHash) {
        database.update("DELETE FROM account_sessions WHERE token_hash = ?", tokenHash);
    }

    void deleteExpiredSessions() {
        database.update("DELETE FROM account_sessions WHERE expires_at <= CURRENT_TIMESTAMP(6)");
    }

    Optional<Account> findByEmail(String email) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM accounts a WHERE a.email = :email")
                .param("email", email)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    Optional<Account> findGoogleAccount(String subject) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + """
                 FROM account_identities i JOIN accounts a ON a.id = i.account_id
                WHERE i.provider = 'google' AND i.subject = :subject
                """)
                .param("subject", subject)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    void insertGoogleIdentity(String accountId, String subject) {
        database.update("INSERT INTO account_identities (provider, subject, account_id) VALUES ('google', ?, ?)",
                subject, accountId);
    }

    void insertGoogleChallenge(byte[] nonceHash, byte[] browserHash, String purpose, Instant expiresAt) {
        database.update("""
                INSERT INTO google_auth_challenges (nonce_hash, browser_hash, purpose, expires_at)
                VALUES (?, ?, ?, ?)
                """, nonceHash, browserHash, purpose, Timestamp.from(expiresAt));
    }

    boolean consumeGoogleChallenge(byte[] nonceHash, byte[] browserHash, String purpose) {
        return database.update("""
                DELETE FROM google_auth_challenges
                WHERE nonce_hash = ? AND browser_hash = ? AND purpose = ? AND expires_at > CURRENT_TIMESTAMP(6)
                """, nonceHash, browserHash, purpose) == 1;
    }

    void deleteExpiredGoogleChallenges() {
        database.update("DELETE FROM google_auth_challenges WHERE expires_at <= CURRENT_TIMESTAMP(6)");
    }

    record Credentials(Account account, String passwordHash) {}
}
