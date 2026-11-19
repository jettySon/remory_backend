CREATE TABLE account_identities (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (provider, subject),
    UNIQUE KEY uq_account_provider (account_id, provider),
    CONSTRAINT fk_identity_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE TABLE google_auth_challenges (
    nonce_hash BINARY(32) PRIMARY KEY,
    browser_hash BINARY(32) NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_google_challenge_expiry (expires_at)
);
