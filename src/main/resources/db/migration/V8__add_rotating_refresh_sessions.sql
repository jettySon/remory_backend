CREATE TABLE account_refresh_families (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_refresh_family_account (account_id),
    INDEX idx_refresh_family_expiry (expires_at),
    CONSTRAINT fk_refresh_family_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE TABLE account_refresh_tokens (
    token_hash BINARY(32) PRIMARY KEY,
    family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    INDEX idx_refresh_token_family (family_id),
    INDEX idx_refresh_token_expiry (expires_at),
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES account_refresh_families (id) ON DELETE CASCADE
);

-- Existing access tokens can be upgraded once; new access tokens belong to a revocable device session.
ALTER TABLE account_sessions
    ADD COLUMN family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD INDEX idx_session_family (family_id),
    ADD CONSTRAINT fk_session_refresh_family FOREIGN KEY (family_id) REFERENCES account_refresh_families (id) ON DELETE CASCADE;
