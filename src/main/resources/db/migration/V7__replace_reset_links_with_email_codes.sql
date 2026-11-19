CREATE TABLE password_reset_challenges (
    challenge_hash BINARY(32) PRIMARY KEY,
    email_hash BINARY(32) NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    code_hash BINARY(32) NOT NULL,
    attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    verified_token_hash BINARY(32) NULL UNIQUE,
    verified_expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_reset_challenge_account (account_id),
    INDEX idx_reset_challenge_email (email_hash, created_at),
    INDEX idx_reset_challenge_expiry (expires_at),
    INDEX idx_reset_verified_expiry (verified_expires_at),
    CONSTRAINT fk_reset_challenge_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

-- Previously issued links are no longer credentials for the new code-based flow.
DROP TABLE password_reset_tokens;
