CREATE TABLE decks (
    row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id VARCHAR(128) NOT NULL,
    title VARCHAR(35) NOT NULL,
    subtitle VARCHAR(70) NOT NULL,
    theme VARCHAR(16) NOT NULL,
    icon VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_deck_account_id (account_id, id),
    INDEX idx_decks_account_order (account_id, row_id),
    CONSTRAINT fk_decks_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
) DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE cards (
    row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id VARCHAR(128) NOT NULL,
    deck_id VARCHAR(128) NOT NULL,
    front MEDIUMTEXT NOT NULL,
    back MEDIUMTEXT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    repetitions INT UNSIGNED NOT NULL,
    kind VARCHAR(16),
    choices JSON,
    UNIQUE KEY uq_card_account_id (account_id, id),
    INDEX idx_cards_account_order (account_id, row_id),
    INDEX idx_cards_deck (account_id, deck_id),
    INDEX idx_cards_due (account_id, due_at),
    CONSTRAINT fk_cards_deck FOREIGN KEY (account_id, deck_id) REFERENCES decks (account_id, id) ON DELETE CASCADE
) DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE review_logs (
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id VARCHAR(128) NOT NULL,
    card_id VARCHAR(128) NOT NULL,
    rating VARCHAR(16) NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,
    mode VARCHAR(16),
    PRIMARY KEY (account_id, id),
    INDEX idx_reviews_date (account_id, reviewed_at),
    INDEX idx_reviews_card_date (account_id, card_id, reviewed_at),
    CONSTRAINT fk_reviews_card FOREIGN KEY (account_id, card_id) REFERENCES cards (account_id, id) ON DELETE CASCADE
) DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_bin;
