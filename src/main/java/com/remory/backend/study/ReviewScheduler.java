package com.remory.backend.study;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class ReviewScheduler {

    static final int HISTORY_LIMIT = 32;
    private static final double TARGET_RECALL = 0.85;
    // 기억률 R(t) = exp(-t / stability)가 목표치에 도달하는 시간은 -stability × ln(TARGET_RECALL)이다.
    private static final double DECAY_TO_TARGET = -Math.log(TARGET_RECALL);
    private static final long MINUTE = 60;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final long MAX_INTERVAL = 180 * DAY;

    private ReviewScheduler() {}

    // 과거 평가를 오래된 순서로 다시 적용해 카드의 기억 안정도를 복원한다.
    // 계산식과 이력 범위는 front/src/study/reviewScheduler.ts의 비회원 계산과 맞춘다.
    static Instant nextDueAt(List<StudyData.Review> history, StudyData.Review review) {
        double stability = 0;
        Instant previous = null;
        for (var entry : history) {
            stability = updateStability(stability, previous, entry);
            previous = entry.date();
        }
        stability = updateStability(stability, previous, review);
        return review.date().plusSeconds(Math.round(stability * DECAY_TO_TARGET));
    }

    private static double updateStability(double stability, Instant previous, StudyData.Review review) {
        if (previous == null) {
            return initialInterval(review.rating()) / DECAY_TO_TARGET;
        }

        double elapsedSeconds = Math.max(0, Duration.between(previous, review.date()).toMillis() / 1000.0);
        // 잊을 가능성이 높았던 시점에도 기억해 냈다면 보통·쉬움의 간격을 더 크게 늘린다.
        double forgotten = -Math.expm1(-elapsedSeconds / stability);
        double priorInterval = stability * DECAY_TO_TARGET;
        double interval = switch (review.rating()) {
            case "again" -> clamp(priorInterval * (0.30 + 0.25 * forgotten), MINUTE, 10 * MINUTE);
            case "hard" -> clamp(priorInterval * (0.70 + 0.25 * forgotten), 15 * MINUTE, MAX_INTERVAL);
            case "good" -> clamp(priorInterval * (1.15 + 1.65 * forgotten), 60 * MINUTE, MAX_INTERVAL);
            case "easy" -> clamp(priorInterval * (1.40 + 2.20 * forgotten), 6 * 60 * MINUTE, MAX_INTERVAL);
            default -> throw new IllegalArgumentException("Unknown rating");
        };
        return interval / DECAY_TO_TARGET;
    }

    private static long initialInterval(String rating) {
        return switch (rating) {
            case "again" -> 10 * MINUTE;
            case "hard" -> DAY;
            case "good" -> 3 * DAY;
            case "easy" -> 7 * DAY;
            default -> throw new IllegalArgumentException("Unknown rating");
        };
    }

    private static double clamp(double value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
