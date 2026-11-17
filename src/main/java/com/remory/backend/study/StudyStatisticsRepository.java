package com.remory.backend.study;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.remory.backend.study.StudyReport.CardCounts;
import com.remory.backend.study.StudyReport.Counts;
import com.remory.backend.study.StudyReport.DeckCounts;
import com.remory.backend.study.StudyReport.ReviewTotals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StudyStatisticsRepository {

    private static final String TOTALS = """
            COUNT(*) AS total,
            SUM(l.rating = 'again') AS again,
            SUM(l.rating = 'hard') AS hard,
            SUM(l.rating = 'good') AS good,
            SUM(l.rating = 'easy') AS easy
            """;
    private static final String CARD_JOIN = """
             JOIN cards c ON c.account_id = l.account_id AND c.id = l.card_id
            """;

    private final JdbcTemplate database;
    private final JdbcClient jdbc;

    public StudyStatisticsRepository(JdbcTemplate database, JdbcClient jdbc) {
        this.database = database;
        this.jdbc = jdbc;
    }

    ReviewTotals countReviews(String accountId) {
        return jdbc.sql("""
                SELECT COUNT(*) AS total, COALESCE(SUM(mode = 'free'), 0) AS free
                FROM review_logs WHERE account_id = :accountId
                """)
                .param("accountId", accountId)
                .query((row, index) -> new ReviewTotals(row.getLong("total"), row.getLong("free")))
                .single();
    }

    List<DeckCounts> countByDeck(String accountId, Instant from) {
        String sql = "SELECT c.deck_id, " + TOTALS + " FROM review_logs l" + CARD_JOIN
                + " WHERE l.account_id = ?" + (from == null ? "" : " AND l.reviewed_at >= ?")
                + " GROUP BY c.deck_id ORDER BY again DESC, again / total DESC, hard DESC, c.deck_id";
        Object[] parameters = from == null ? new Object[]{accountId} : new Object[]{accountId, StudyRepository.utc(from)};
        return database.query(sql, (row, index) -> new DeckCounts(row.getString("deck_id"), counts(row)), parameters);
    }

    List<CardCounts> countDifficultCards(String accountId, Instant from, String deckId) {
        // For a card that matched the lower date bound, its latest review also falls in the period.
        String sql = """
                SELECT results.*,
                       (SELECT latest.rating FROM review_logs latest
                        WHERE latest.account_id = ? AND latest.card_id = results.card_id
                        ORDER BY latest.reviewed_at DESC, latest.id DESC LIMIT 1) AS latest_rating
                FROM (
                    SELECT l.card_id, %s
                    FROM review_logs l %s
                    WHERE l.account_id = ? %s %s
                    GROUP BY l.card_id
                    HAVING again > 0
                ) results
                ORDER BY again DESC, again / total DESC, hard DESC, results.card_id
                """.formatted(TOTALS, deckId == null ? "" : CARD_JOIN,
                from == null ? "" : "AND l.reviewed_at >= ?", deckId == null ? "" : "AND c.deck_id = ?");
        var parameters = new ArrayList<Object>(List.of(accountId, accountId));
        if (from != null) parameters.add(StudyRepository.utc(from));
        if (deckId != null) parameters.add(deckId);
        return database.query(sql, (row, index) ->
                new CardCounts(row.getString("card_id"), counts(row), row.getString("latest_rating")), parameters.toArray());
    }

    Map<LocalDate, Counts> countByDay(String accountId, Instant from, Instant to, ZoneId zone, String deckId) {
        String sql = "SELECT l.reviewed_at, l.rating FROM review_logs l" + (deckId == null ? "" : CARD_JOIN)
                + " WHERE l.account_id = ? AND l.reviewed_at >= ? AND l.reviewed_at < ?"
                + (deckId == null ? "" : " AND c.deck_id = ?");
        var parameters = new ArrayList<Object>(List.of(accountId, StudyRepository.utc(from), StudyRepository.utc(to)));
        if (deckId != null) parameters.add(deckId);

        var days = new LinkedHashMap<LocalDate, Counts>();
        database.query(sql, row -> {
            var day = StudyRepository.instant(row, "reviewed_at").atZone(zone).toLocalDate();
            String rating = row.getString("rating");
            days.put(day, days.getOrDefault(day, Counts.EMPTY).add(rating));
        }, parameters.toArray());
        return days;
    }

    private static Counts counts(ResultSet row) throws SQLException {
        return new Counts(row.getLong("total"), row.getLong("again"), row.getLong("hard"),
                row.getLong("good"), row.getLong("easy"));
    }
}
