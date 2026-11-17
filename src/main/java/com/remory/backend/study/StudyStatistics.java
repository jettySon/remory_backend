package com.remory.backend.study;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.remory.backend.study.StudyReport.Counts;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyStatistics {

    private final StudyStatisticsRepository repository;

    public StudyStatistics(StudyStatisticsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public StudyReport report(String accountId, String period, String deckId, String timeZone) {
        ZoneId zone = zone(timeZone);
        LocalDate today = LocalDate.now(zone);
        LocalDate start = switch (period) {
            case "all" -> null;
            case "7" -> today.minusDays(6);
            case "30" -> today.minusDays(29);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "집계 기간을 확인해 주세요.");
        };
        Instant from = start == null ? null : startOf(start, zone);
        var totals = repository.countReviews(accountId);
        var deckTotals = repository.countByDeck(accountId, from);
        var difficultCards = repository.countDifficultCards(accountId, from, deckId);
        var week = repository.countByDay(accountId, startOf(today.minusDays(6), zone),
                startOf(today.plusDays(1), zone), zone, deckId);
        return new StudyReport(totals, deckTotals, difficultCards, week);
    }

    public Map<LocalDate, Counts> activity(String accountId, LocalDate from, LocalDate to, String timeZone) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1 || days > 31 || from.getYear() < 1000 || to.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "최대 31일의 학습 기록을 조회할 수 있어요.");
        }
        ZoneId zone = zone(timeZone);
        return repository.countByDay(accountId, startOf(from, zone), startOf(to, zone), zone, null);
    }

    private static Instant startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    private static ZoneId zone(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시간대를 확인해 주세요.", error);
        }
    }
}
