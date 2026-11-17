package com.remory.backend.study;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remory.backend.study.StudyData.ReviewProgress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StudyRepository {

    private static final String DECK_FIELDS = "id, title, subtitle, theme, icon";
    private static final String CARD_FIELDS = "id, deck_id, front, back, due_at, repetitions, kind, choices";
    private static final String REVIEW_FIELDS = "id, card_id, rating, reviewed_at, mode";
    private static final String INSERT_DECK = """
            INSERT INTO decks (account_id, %s) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = decks.id
            """.formatted(DECK_FIELDS);
    private static final String INSERT_CARD = """
            INSERT INTO cards (account_id, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = cards.id
            """.formatted(CARD_FIELDS);
    private static final String INSERT_REVIEW = "INSERT INTO review_logs (account_id, " + REVIEW_FIELDS
            + ") VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate database;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public StudyRepository(JdbcTemplate database, JdbcClient jdbc, ObjectMapper json) {
        this.database = database;
        this.jdbc = jdbc;
        this.json = json;
    }

    List<StudyData.Deck> findDecks(String accountId) {
        return jdbc.sql("SELECT " + DECK_FIELDS + " FROM decks WHERE account_id = :accountId ORDER BY row_id")
                .param("accountId", accountId)
                .query(this::deck)
                .list();
    }

    List<StudyData.Card> findCards(String accountId) {
        return jdbc.sql("SELECT " + CARD_FIELDS + " FROM cards WHERE account_id = :accountId ORDER BY row_id")
                .param("accountId", accountId)
                .query(this::card)
                .list();
    }

    void insertDeck(String accountId, StudyData.Deck deck) {
        database.update(INSERT_DECK, deckValues(accountId, deck));
    }

    StudyData.Deck getDeck(String accountId, String deckId) {
        return jdbc.sql("SELECT " + DECK_FIELDS + " FROM decks WHERE account_id = :accountId AND id = :deckId")
                .param("accountId", accountId)
                .param("deckId", deckId)
                .query(this::deck)
                .single();
    }

    boolean lockDeck(String accountId, String deckId) {
        return jdbc.sql("SELECT id FROM decks WHERE account_id = :accountId AND id = :deckId FOR UPDATE")
                .param("accountId", accountId)
                .param("deckId", deckId)
                .query(String.class)
                .optional()
                .isPresent();
    }

    List<StudyData.Card> findCardsForUpdate(String accountId, String deckId, List<String> ids) {
        return jdbc.sql("SELECT " + CARD_FIELDS + " FROM cards WHERE account_id = :accountId"
                        + " AND deck_id = :deckId AND id IN (:ids) ORDER BY row_id FOR UPDATE")
                .param("accountId", accountId)
                .param("deckId", deckId)
                .param("ids", ids)
                .query(this::card)
                .list();
    }

    Optional<ReviewProgress> findProgressForUpdate(String accountId, String cardId) {
        return jdbc.sql("SELECT id, due_at, repetitions FROM cards"
                        + " WHERE account_id = :accountId AND id = :cardId FOR UPDATE")
                .param("accountId", accountId)
                .param("cardId", cardId)
                .query((row, index) -> new ReviewProgress(row.getString("id"), instant(row, "due_at"), row.getInt("repetitions")))
                .optional();
    }

    void insertReview(String accountId, StudyData.Review review) {
        database.update(INSERT_REVIEW, reviewValues(accountId, review));
    }

    StudyData.Review getReviewForUpdate(String accountId, String reviewId) {
        return jdbc.sql("SELECT " + REVIEW_FIELDS
                        + " FROM review_logs WHERE account_id = :accountId AND id = :reviewId FOR UPDATE")
                .param("accountId", accountId)
                .param("reviewId", reviewId)
                .query(this::review)
                .single();
    }

    List<StudyData.Review> findRecentScheduledReviews(String accountId, StudyData.Review review, int limit) {
        return jdbc.sql("""
                SELECT %s FROM review_logs
                WHERE account_id = :accountId AND card_id = :cardId
                  AND (mode = 'scheduled' OR mode IS NULL)
                  AND reviewed_at <= :reviewedAt AND id <> :reviewId
                ORDER BY reviewed_at DESC, id DESC
                LIMIT :limit
                """.formatted(REVIEW_FIELDS))
                .param("accountId", accountId)
                .param("cardId", review.cardId())
                .param("reviewedAt", utc(review.date()))
                .param("reviewId", review.id())
                .param("limit", limit)
                .query(this::review)
                .list();
    }

    void updateSchedule(String accountId, ReviewProgress progress) {
        database.update("UPDATE cards SET due_at = ?, repetitions = ? WHERE account_id = ? AND id = ?",
                utc(progress.dueAt()), progress.repetitions(), accountId, progress.id());
    }

    void insertDecks(String accountId, List<StudyData.Deck> decks) {
        if (!decks.isEmpty()) {
            database.batchUpdate(INSERT_DECK, decks.stream().map(deck -> deckValues(accountId, deck)).toList());
        }
    }

    void insertCards(String accountId, List<StudyData.Card> cards) {
        if (!cards.isEmpty()) {
            database.batchUpdate(INSERT_CARD, cards.stream().map(card -> new Object[]{
                    accountId, card.id(), card.deckId(), card.front(), card.back(), utc(card.dueAt()),
                    card.repetitions(), card.kind(), encodeChoices(card.choices())
            }).toList());
        }
    }

    void importReviews(String accountId, List<StudyData.Review> reviews) {
        if (!reviews.isEmpty()) {
            database.batchUpdate(INSERT_REVIEW + " ON DUPLICATE KEY UPDATE id = review_logs.id",
                    reviews.stream().map(review -> reviewValues(accountId, review)).toList());
        }
    }

    private StudyData.Deck deck(ResultSet row, int index) throws SQLException {
        return new StudyData.Deck(row.getString("id"), row.getString("title"), row.getString("subtitle"),
                row.getString("theme"), row.getString("icon"));
    }

    private StudyData.Card card(ResultSet row, int index) throws SQLException {
        return new StudyData.Card(row.getString("id"), row.getString("deck_id"), row.getString("front"),
                row.getString("back"), instant(row, "due_at"), row.getInt("repetitions"),
                row.getString("kind"), decodeChoices(row.getString("choices")));
    }

    private StudyData.Review review(ResultSet row, int index) throws SQLException {
        return new StudyData.Review(row.getString("id"), row.getString("card_id"), row.getString("rating"),
                instant(row, "reviewed_at"), row.getString("mode"));
    }

    private List<String> decodeChoices(String value) {
        try {
            return value == null ? null : json.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not read card choices", error);
        }
    }

    private String encodeChoices(List<String> value) {
        try {
            return value == null ? null : json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not store card choices", error);
        }
    }

    private static Object[] deckValues(String accountId, StudyData.Deck deck) {
        return new Object[]{accountId, deck.id(), deck.title(), deck.subtitle(), deck.theme(), deck.icon()};
    }

    private static Object[] reviewValues(String accountId, StudyData.Review review) {
        return new Object[]{accountId, review.id(), review.cardId(), review.rating(), utc(review.date()), review.mode()};
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

}
