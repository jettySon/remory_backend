START TRANSACTION;

INSERT INTO decks (account_id, id, title, subtitle, theme, icon)
SELECT s.account_id, d.id, d.title, d.subtitle, d.theme, d.icon
FROM study_snapshots s
JOIN JSON_TABLE(s.study_data, '$.decks[*]' COLUMNS (
    ordinal FOR ORDINALITY,
    id VARCHAR(128) PATH '$.id',
    title VARCHAR(35) PATH '$.title',
    subtitle VARCHAR(70) PATH '$.subtitle',
    theme VARCHAR(16) PATH '$.theme',
    icon VARCHAR(32) PATH '$.icon'
)) d
ORDER BY s.account_id, d.ordinal;

INSERT INTO cards (account_id, id, deck_id, front, back, due_at, repetitions, kind, choices)
SELECT s.account_id, c.id, c.deck_id, c.front, c.back,
       CAST(LEFT(REPLACE(REPLACE(c.due_at, 'T', ' '), 'Z', ''), 26) AS DATETIME(6)),
       c.repetitions, c.kind, c.choices
FROM study_snapshots s
JOIN JSON_TABLE(s.study_data, '$.cards[*]' COLUMNS (
    ordinal FOR ORDINALITY,
    id VARCHAR(128) PATH '$.id',
    deck_id VARCHAR(128) PATH '$.deckId',
    front MEDIUMTEXT PATH '$.front',
    back MEDIUMTEXT PATH '$.back',
    due_at VARCHAR(40) PATH '$.dueAt',
    repetitions INT PATH '$.repetitions',
    kind VARCHAR(16) PATH '$.kind',
    choices JSON PATH '$.choices'
)) c
ORDER BY s.account_id, c.ordinal;

INSERT INTO review_logs (account_id, id, card_id, rating, reviewed_at, mode)
SELECT s.account_id, r.id, r.card_id, r.rating,
       CAST(LEFT(REPLACE(REPLACE(r.reviewed_at, 'T', ' '), 'Z', ''), 26) AS DATETIME(6)), r.mode
FROM study_snapshots s
JOIN JSON_TABLE(s.study_data, '$.logs[*]' COLUMNS (
    id VARCHAR(128) PATH '$.id',
    card_id VARCHAR(128) PATH '$.cardId',
    rating VARCHAR(16) PATH '$.rating',
    reviewed_at VARCHAR(40) PATH '$.date',
    mode VARCHAR(16) PATH '$.mode'
)) r;

COMMIT;
