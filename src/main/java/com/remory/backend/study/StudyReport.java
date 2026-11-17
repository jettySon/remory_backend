package com.remory.backend.study;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record StudyReport(
        ReviewTotals totals,
        List<DeckCounts> decks,
        List<CardCounts> cards,
        Map<LocalDate, Counts> week
) {
    public record Counts(long total, long again, long hard, long good, long easy) {
        static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);

        Counts add(String rating) {
            return new Counts(total + 1, again + ("again".equals(rating) ? 1 : 0), hard + ("hard".equals(rating) ? 1 : 0),
                    good + ("good".equals(rating) ? 1 : 0), easy + ("easy".equals(rating) ? 1 : 0));
        }
    }

    public record ReviewTotals(long total, long free) {}
    public record DeckCounts(String id, Counts counts) {}
    public record CardCounts(String id, Counts counts, String latestRating) {}
}
