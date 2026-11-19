-- Never revive credentials that expired under the previous inactivity policy.
DELETE FROM account_refresh_families WHERE expires_at <= CURRENT_TIMESTAMP(6);
DELETE FROM account_refresh_tokens WHERE expires_at <= CURRENT_TIMESTAMP(6);

-- NULL means valid until logout, password reset, account deletion, or replay revocation.
ALTER TABLE account_refresh_families MODIFY COLUMN expires_at TIMESTAMP(6) NULL DEFAULT NULL;
ALTER TABLE account_refresh_tokens MODIFY COLUMN expires_at TIMESTAMP(6) NULL DEFAULT NULL;

UPDATE account_refresh_families SET expires_at = NULL;
UPDATE account_refresh_tokens SET expires_at = NULL WHERE used_at IS NULL;
-- Consumed tokens retain finite expiry so replay history can still be pruned.
