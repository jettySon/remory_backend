CREATE TABLE accounts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE account_passwords (
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    password_hash VARCHAR(255) CHARACTER SET ascii NOT NULL,
    CONSTRAINT fk_password_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE TABLE account_sessions (
    token_hash BINARY(32) PRIMARY KEY,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_session_expiry (expires_at),
    CONSTRAINT fk_session_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE TABLE study_snapshots (
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    study_data JSON NOT NULL,
    CONSTRAINT fk_snapshot_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);
