package com.remory.backend.study;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyService {

    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final Validator validator;

    public StudyService(JdbcTemplate database, ObjectMapper json, Validator validator) {
        this.database = database;
        this.json = json;
        this.validator = validator;
    }

    public Snapshot read(String accountId) {
        return read(accountId, false);
    }

    @Transactional
    public Snapshot replace(String accountId, long revision, StudyData data) {
        Snapshot current = read(accountId, true);
        if (current.revision() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "학습 기록이 바뀌었어요. 최신 데이터를 먼저 불러와 주세요.");
        }
        return save(accountId, current, data);
    }

    @Transactional
    public Snapshot importGuest(String accountId, StudyData guest) {
        Snapshot current = read(accountId, true);
        StudyData owned = current.data();
        StudyData merged = new StudyData(
                merge(owned.decks(), guest.decks(), StudyData.Deck::id),
                merge(owned.cards(), guest.cards(), StudyData.Card::id),
                merge(owned.logs(), guest.logs().stream()
                        .filter(log -> !("seed".equals(log.cardId()) && log.id().startsWith("seed-"))).toList(),
                        StudyData.Review::id)
        );
        return save(accountId, current, merged);
    }

    private Snapshot read(String accountId, boolean lock) {
        String sql = "SELECT revision, study_data FROM study_snapshots WHERE account_id = ?";
        if (lock) {
            sql += " FOR UPDATE";
        }
        return database.queryForObject(sql, (row, index) ->
                new Snapshot(row.getLong("revision"), decode(row.getString("study_data"))), accountId);
    }

    private Snapshot save(String accountId, Snapshot current, StudyData next) {
        validate(next);
        if (current.data().equals(next)) {
            return current;
        }
        long revision = current.revision() + 1;
        database.update("UPDATE study_snapshots SET revision = ?, study_data = ? WHERE account_id = ?",
                revision, encode(next), accountId);
        return new Snapshot(revision, next);
    }

    private void validate(StudyData data) {
        if (!validator.validate(data).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학습 데이터의 형식이나 개수를 확인해 주세요.");
        }
        Set<String> deckIds = distinctIds(data.decks(), StudyData.Deck::id);
        Set<String> cardIds = distinctIds(data.cards(), StudyData.Card::id);
        distinctIds(data.logs(), StudyData.Review::id);
        if (data.cards().stream().anyMatch(card -> !deckIds.contains(card.deckId()))
                || data.logs().stream().anyMatch(log -> !cardIds.contains(log.cardId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카드가 속한 덱과 복습 기록이 참조하는 카드를 확인해 주세요.");
        }
    }

    private static <T> Set<String> distinctIds(List<T> items, Function<T, String> id) {
        Set<String> ids = new HashSet<>();
        for (T item : items) {
            if (!ids.add(id.apply(item))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 ID가 두 번 들어 있어요.");
            }
        }
        return ids;
    }

    private static <T> List<T> merge(List<T> owned, List<T> imported, Function<T, String> id) {
        var items = new LinkedHashMap<String, T>();
        owned.forEach(item -> items.put(id.apply(item), item));
        imported.forEach(item -> items.putIfAbsent(id.apply(item), item));
        return List.copyOf(items.values());
    }

    private StudyData decode(String data) {
        try {
            return json.readValue(data, StudyData.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not decode study snapshot", error);
        }
    }

    private String encode(StudyData data) {
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode study snapshot", error);
        }
    }

    public record Snapshot(long revision, StudyData data) {}
}
