-- Historical reviews did not measure the first attempt and stay NULL.
ALTER TABLE review_logs ADD COLUMN first_recall VARCHAR(16) NULL;
