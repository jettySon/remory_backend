package com.remory.backend.study;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSchedulerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void correctlyRecallingTheSameCardExtendsItsInterval() {
        var first = review("first", "good", START);
        var nextDate = ReviewScheduler.nextDueAt(List.of(), first);
        assertThat(nextDate).isEqualTo(START.plus(Duration.ofDays(3)));

        var second = review("second", "good", nextDate);
        var interval = intervalAfter(List.of(first), second);
        assertThat(interval).isGreaterThan(Duration.ofDays(3));

        var third = review("third", "good", nextDate.plus(interval));
        assertThat(intervalAfter(List.of(first, second), third)).isGreaterThan(interval);
    }

    @Test
    void aSuccessfulRecallAfterALongerGapEarnsALongerInterval() {
        var first = review("first", "good", START);
        var early = review("second", "good", START.plus(Duration.ofHours(1)));
        var late = review("second", "good", START.plus(Duration.ofDays(6)));

        assertThat(intervalAfter(List.of(first), late)).isGreaterThan(intervalAfter(List.of(first), early));
    }

    @Test
    void repeatedLapsesShortenTheIntervalAndLimitGrowthOnRecovery() {
        var first = review("first", "again", START);
        var relearnAt = ReviewScheduler.nextDueAt(List.of(), first);
        var second = review("second", "again", relearnAt);
        var nextInterval = intervalAfter(List.of(first), second);
        assertThat(nextInterval).isLessThan(Duration.ofMinutes(10)).isGreaterThanOrEqualTo(Duration.ofMinutes(1));

        var recovered = review("third", "good", relearnAt.plus(nextInterval));
        assertThat(intervalAfter(List.of(first, second), recovered)).isLessThan(Duration.ofDays(3));
    }

    @Test
    void limitsLongSequencesOfSuccessfulReviews() {
        var history = new ArrayList<StudyData.Review>();
        Instant reviewedAt = START;
        for (int i = 0; i < ReviewScheduler.HISTORY_LIMIT; i++) {
            var entry = review("success-" + i, "easy", reviewedAt);
            reviewedAt = ReviewScheduler.nextDueAt(history, entry);
            history.add(entry);
        }

        assertThat(intervalAfter(history, review("next", "easy", reviewedAt))).isEqualTo(Duration.ofDays(180));
    }

    private static Duration intervalAfter(List<StudyData.Review> history, StudyData.Review entry) {
        return Duration.between(entry.date(), ReviewScheduler.nextDueAt(history, entry));
    }

    private static StudyData.Review review(String id, String rating, Instant date) {
        return new StudyData.Review(id, "card", rating, date, "scheduled");
    }
}
