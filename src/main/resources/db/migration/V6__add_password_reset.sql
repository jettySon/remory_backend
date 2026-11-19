CREATE TABLE password_reset_tokens (
    token_hash BINARY(32) PRIMARY KEY,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_password_reset_account (account_id),
    INDEX idx_password_reset_expiry (expires_at),
    CONSTRAINT fk_password_reset_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

-- Hashes include a time bucket; neither email addresses nor IP addresses are retained here.
CREATE TABLE password_reset_request_limits (
    key_hash BINARY(32) PRIMARY KEY,
    attempts INT UNSIGNED NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_reset_limit_expiry (expires_at)
);
