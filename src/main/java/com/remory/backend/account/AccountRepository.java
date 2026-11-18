package com.remory.backend.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate database;
    private final JdbcClient jdbc;

    public AccountRepository(JdbcTemplate database, JdbcClient jdbc) {
        this.database = database;
        this.jdbc = jdbc;
    }

    void insertAccount(Account account) {
        database.update("INSERT INTO accounts (id, email) VALUES (?, ?)", account.id(), account.email());
    }

    void insertPassword(String accountId, String passwordHash) {
        database.update("INSERT INTO account_passwords (account_id, password_hash) VALUES (?, ?)",
                accountId, passwordHash);
    }

    Optional<Credentials> findCredentials(String email) {
        return jdbc.sql("""
                SELECT a.id, a.email, p.password_hash
                FROM accounts a JOIN account_passwords p ON p.account_id = a.id
                WHERE a.email = :email
                """)
                .param("email", email)
                .query((row, index) -> new Credentials(
                        new Account(row.getString("id"), row.getString("email")), row.getString("password_hash")))
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
        return jdbc.sql("""
                SELECT a.id, a.email FROM account_sessions s
                JOIN accounts a ON a.id = s.account_id
                WHERE s.token_hash = :tokenHash AND s.expires_at > CURRENT_TIMESTAMP(6)
                """)
                .param("tokenHash", tokenHash)
                .query((row, index) -> new Account(row.getString("id"), row.getString("email")))
                .optional();
    }

    void insertSession(String accountId, byte[] tokenHash, Instant expiresAt) {
        database.update("""
                INSERT INTO account_sessions (token_hash, account_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, accountId, Timestamp.from(expiresAt));
    }

    void deleteSession(byte[] tokenHash) {
        database.update("DELETE FROM account_sessions WHERE token_hash = ?", tokenHash);
    }

    void deleteExpiredSessions() {
        database.update("DELETE FROM account_sessions WHERE expires_at <= CURRENT_TIMESTAMP(6)");
    }

    record Credentials(Account account, String passwordHash) {}
}
