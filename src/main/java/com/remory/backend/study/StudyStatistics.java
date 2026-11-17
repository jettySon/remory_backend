package com.remory.backend.study;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyStatistics {

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

    public StudyStatistics(JdbcTemplate database, JdbcClient jdbc) {
        this.database = database;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report report(String accountId, String period, String deckId, String timeZone) {
        ZoneId zone = zone(timeZone);
        LocalDate today = LocalDate.now(zone);
        LocalDate start = switch (period) {
            case "all" -> null;
            case "7" -> today.minusDays(6);
            case "30" -> today.minusDays(29);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "집계 기간을 확인해 주세요.");
        };
        var totals = jdbc.sql("""
                SELECT COUNT(*) AS total, COALESCE(SUM(mode = 'free'), 0) AS free
                FROM review_logs WHERE account_id = :accountId
                """)
                .param("accountId", accountId)
                .query((row, index) -> new ReviewTotals(row.getLong("total"), row.getLong("free")))
                .single();
        var deckTotals = forDecks(accountId, start, zone);
        var difficultCards = forCards(accountId, start, zone, deckId);
        var week = daily(accountId, today.minusDays(6), today.plusDays(1), zone, deckId);
        return new Report(totals, deckTotals, difficultCards, week);
    }

    public Map<LocalDate, Counts> activity(String accountId, LocalDate from, LocalDate to, String timeZone) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1 || days > 31 || from.getYear() < 1000 || to.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "최대 31일의 학습 기록을 조회할 수 있어요.");
        }
        return daily(accountId, from, to, zone(timeZone), null);
    }

    private List<DeckCounts> forDecks(String accountId, LocalDate start, ZoneId zone) {
        String sql = "SELECT c.deck_id, " + TOTALS + " FROM review_logs l" + CARD_JOIN
                + " WHERE l.account_id = ?" + (start == null ? "" : " AND l.reviewed_at >= ?")
                + " GROUP BY c.deck_id ORDER BY again DESC, again / total DESC, hard DESC, c.deck_id";
        Object[] parameters = start == null ? new Object[]{accountId} : new Object[]{accountId, startOf(start, zone)};
        return database.query(sql, (row, index) -> new DeckCounts(row.getString("deck_id"), counts(row)), parameters);
    }

    private List<CardCounts> forCards(String accountId, LocalDate start, ZoneId zone, String deckId) {
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
                start == null ? "" : "AND l.reviewed_at >= ?", deckId == null ? "" : "AND c.deck_id = ?");
        var parameters = new ArrayList<Object>(List.of(accountId, accountId));
        if (start != null) parameters.add(startOf(start, zone));
        if (deckId != null) parameters.add(deckId);
        return database.query(sql, (row, index) ->
                new CardCounts(row.getString("card_id"), counts(row), row.getString("latest_rating")), parameters.toArray());
    }

    private Map<LocalDate, Counts> daily(String accountId, LocalDate from, LocalDate to, ZoneId zone, String deckId) {
        String sql = "SELECT l.reviewed_at, l.rating FROM review_logs l" + (deckId == null ? "" : CARD_JOIN)
                + " WHERE l.account_id = ? AND l.reviewed_at >= ? AND l.reviewed_at < ?"
                + (deckId == null ? "" : " AND c.deck_id = ?");
        var parameters = new ArrayList<Object>(List.of(accountId, startOf(from, zone), startOf(to, zone)));
        if (deckId != null) parameters.add(deckId);

        var days = new LinkedHashMap<LocalDate, Counts>();
        database.query(sql, row -> {
            var day = StudyService.instant(row, "reviewed_at").atZone(zone).toLocalDate();
            String rating = row.getString("rating");
            days.put(day, days.getOrDefault(day, Counts.EMPTY).add(rating));
        }, parameters.toArray());
        return days;
    }

    private static Counts counts(ResultSet row) throws SQLException {
        return new Counts(row.getLong("total"), row.getLong("again"), row.getLong("hard"),
                row.getLong("good"), row.getLong("easy"));
    }

    private static LocalDateTime startOf(LocalDate day, ZoneId zone) {
        return StudyService.utc(day.atStartOfDay(zone).toInstant());
    }

    private static ZoneId zone(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시간대를 확인해 주세요.", error);
        }
    }

    public record Counts(long total, long again, long hard, long good, long easy) {
        private static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);

        private Counts add(String rating) {
            return new Counts(total + 1, again + ("again".equals(rating) ? 1 : 0), hard + ("hard".equals(rating) ? 1 : 0),
                    good + ("good".equals(rating) ? 1 : 0), easy + ("easy".equals(rating) ? 1 : 0));
        }
    }

    public record ReviewTotals(long total, long free) {}
    public record DeckCounts(String id, Counts counts) {}
    public record CardCounts(String id, Counts counts, String latestRating) {}
    public record Report(ReviewTotals totals, List<DeckCounts> decks, List<CardCounts> cards, Map<LocalDate, Counts> week) {}
}
