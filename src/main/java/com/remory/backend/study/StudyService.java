package com.remory.backend.study;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyService {

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

    public StudyService(JdbcTemplate database, JdbcClient jdbc, ObjectMapper json) {
        this.database = database;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Library read(String accountId) {
        var decks = jdbc.sql("SELECT " + DECK_FIELDS + " FROM decks WHERE account_id = :accountId ORDER BY row_id")
                .param("accountId", accountId)
                .query(this::deck)
                .list();
        var cards = jdbc.sql("SELECT " + CARD_FIELDS + " FROM cards WHERE account_id = :accountId ORDER BY row_id")
                .param("accountId", accountId)
                .query(this::card)
                .list();
        return new Library(decks, cards);
    }

    @Transactional
    public StudyData.Deck createDeck(String accountId, StudyData.Deck deck) {
        database.update(INSERT_DECK, deckValues(accountId, deck));
        return jdbc.sql("SELECT " + DECK_FIELDS + " FROM decks WHERE account_id = :accountId AND id = :deckId")
                .param("accountId", accountId)
                .param("deckId", deck.id())
                .query(this::deck)
                .single();
    }

    @Transactional
    public List<StudyData.Card> createCards(String accountId, String deckId, List<StudyData.NewCard> drafts) {
        jdbc.sql("SELECT id FROM decks WHERE account_id = :accountId AND id = :deckId FOR UPDATE")
                .param("accountId", accountId)
                .param("deckId", deckId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 덱을 찾지 못했어요."));
        var ids = drafts.stream().map(StudyData.NewCard::id).distinct().toList();
        if (ids.size() != drafts.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 카드가 두 번 들어 있어요.");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var cards = drafts.stream().map(draft -> new StudyData.Card(draft.id(), deckId, draft.front(),
                draft.back(), now, 0, draft.kind(), draft.choices())).toList();
        insertCards(accountId, cards);
        var saved = jdbc.sql("SELECT " + CARD_FIELDS + " FROM cards WHERE account_id = :accountId"
                        + " AND deck_id = :deckId AND id IN (:ids) ORDER BY row_id FOR UPDATE")
                .param("accountId", accountId)
                .param("deckId", deckId)
                .param("ids", ids)
                .query(this::card)
                .list();
        if (saved.size() != drafts.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 덱에 같은 카드 ID가 있어요.");
        }
        return saved;
    }

    @Transactional
    public RecordedReview recordReview(String accountId, StudyData.NewReview request) {
        var progress = jdbc.sql("SELECT id, due_at, repetitions FROM cards"
                        + " WHERE account_id = :accountId AND id = :cardId FOR UPDATE")
                .param("accountId", accountId)
                .param("cardId", request.cardId())
                .query((row, index) -> new ReviewProgress(row.getString("id"), instant(row, "due_at"), row.getInt("repetitions")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "복습할 카드를 찾지 못했어요."));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var review = new StudyData.Review(request.id(), request.cardId(), request.rating(), now, request.mode());
        try {
            database.update(INSERT_REVIEW, reviewValues(accountId, review));
        } catch (DuplicateKeyException ignored) {
            var existing = jdbc.sql("SELECT " + REVIEW_FIELDS
                            + " FROM review_logs WHERE account_id = :accountId AND id = :reviewId FOR UPDATE")
                    .param("accountId", accountId)
                    .param("reviewId", request.id())
                    .query(this::review)
                    .single();
            if (!existing.cardId().equals(request.cardId())
                    || !existing.rating().equals(request.rating()) || !request.mode().equals(existing.mode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 복습 ID예요.");
            }
            return new RecordedReview(progress, existing);
        }
        if ("scheduled".equals(request.mode())) {
            progress = new ReviewProgress(progress.id(), now.plusSeconds(reviewDelay(request.rating())),
                    progress.repetitions() + 1);
            database.update("UPDATE cards SET due_at = ?, repetitions = ? WHERE account_id = ? AND id = ?",
                    utc(progress.dueAt()), progress.repetitions(), accountId, progress.id());
        }
        return new RecordedReview(progress, review);
    }

    @Transactional
    public void importGuest(String accountId, StudyData guest) {
        try {
            if (!guest.decks().isEmpty()) {
                database.batchUpdate(INSERT_DECK, guest.decks().stream().map(deck -> deckValues(accountId, deck)).toList());
            }
            insertCards(accountId, guest.cards());
            var reviews = guest.logs().stream()
                    .filter(log -> !("seed".equals(log.cardId()) && log.id().startsWith("seed-")))
                    .map(log -> reviewValues(accountId, log)).toList();
            if (!reviews.isEmpty()) {
                database.batchUpdate(INSERT_REVIEW + " ON DUPLICATE KEY UPDATE id = review_logs.id", reviews);
            }
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카드가 속한 덱과 복습 기록이 참조하는 카드를 확인해 주세요.", error);
        }
    }

    private void insertCards(String accountId, List<StudyData.Card> cards) {
        if (!cards.isEmpty()) {
            database.batchUpdate(INSERT_CARD, cards.stream().map(card -> new Object[]{
                    accountId, card.id(), card.deckId(), card.front(), card.back(), utc(card.dueAt()),
                    card.repetitions(), card.kind(), encodeChoices(card.choices())
            }).toList());
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

    private static long reviewDelay(String rating) {
        return switch (rating) {
            case "again" -> 10 * 60;
            case "hard" -> 86_400;
            case "good" -> 3 * 86_400;
            case "easy" -> 7 * 86_400;
            default -> throw new IllegalArgumentException("Unknown rating");
        };
    }

    public record Library(List<StudyData.Deck> decks, List<StudyData.Card> cards) {}
    public record ReviewProgress(String id, Instant dueAt, int repetitions) {}
    public record RecordedReview(ReviewProgress progress, StudyData.Review review) {}
}
