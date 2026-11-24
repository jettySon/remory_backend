package com.remory.backend.study;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import com.remory.backend.study.StudyData.ReviewProgress;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyService {

    private final StudyRepository repository;

    public StudyService(StudyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Library read(String accountId) {
        return new Library(repository.findDecks(accountId), repository.findCards(accountId),
                repository.findLatestReviews(accountId));
    }

    @Transactional
    public StudyData.Deck createDeck(String accountId, StudyData.Deck deck) {
        repository.insertDeck(accountId, deck);
        return repository.getDeck(accountId, deck.id());
    }

    @Transactional
    public StudyData.Deck editDeck(String accountId, String deckId, StudyData.DeckContent content) {
        if (!repository.lockDeck(accountId, deckId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수정할 덱을 찾지 못했어요.");
        }
        var normalized = new StudyData.DeckContent(content.title().strip(), content.subtitle().strip(), content.theme());
        repository.updateDeckContent(accountId, deckId, normalized);
        return repository.getDeck(accountId, deckId);
    }

    @Transactional
    public void deleteDeck(String accountId, String deckId) {
        // 없는 덱의 재삭제도 성공으로 처리해 응답 유실 후 안전하게 재시도한다.
        repository.deleteDeck(accountId, deckId);
    }

    @Transactional
    public List<StudyData.Card> createCards(String accountId, String deckId, List<StudyData.NewCard> drafts) {
        if (!repository.lockDeck(accountId, deckId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 덱을 찾지 못했어요.");
        }
        var ids = drafts.stream().map(StudyData.NewCard::id).distinct().toList();
        if (ids.size() != drafts.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 카드가 두 번 들어 있어요.");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var cards = drafts.stream().map(draft -> new StudyData.Card(draft.id(), deckId, draft.front(),
                draft.back(), now, 0, draft.kind(), draft.choices())).toList();
        repository.insertCards(accountId, cards);
        var saved = repository.findCardsForUpdate(accountId, deckId, ids);
        if (saved.size() != drafts.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 덱에 같은 카드 ID가 있어요.");
        }
        return saved;
    }

    @Transactional
    public StudyData.Card editCard(String accountId, String cardId, StudyData.CardContent content) {
        var card = repository.findCardForUpdate(accountId, cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "수정할 카드를 찾지 못했어요."));
        boolean multipleChoice = "choice".equals(card.kind()) || (card.choices() != null && !card.choices().isEmpty());
        var choices = content.choices() == null ? card.choices() : content.choices();
        if (multipleChoice) {
            if (choices == null || choices.size() != 3 || !choices.contains(content.back())
                    || choices.stream().map(String::strip).distinct().count() != 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정답과 서로 다른 오답 두 개를 입력해 주세요.");
            }
        } else if (content.choices() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "객관식 카드에만 보기를 입력할 수 있어요.");
        }

        // 내용만 쓰고, 잠금 상태에서 읽은 복습일과 횟수를 API 응답에 그대로 돌려준다.
        repository.updateCardContent(accountId, cardId, new StudyData.CardContent(content.front(), content.back(), choices));
        return new StudyData.Card(card.id(), card.deckId(), content.front(), content.back(),
                card.dueAt(), card.repetitions(), card.kind(), choices);
    }

    @Transactional
    public void deleteCard(String accountId, String cardId) {
        // 카드 삭제 시 외래 키 제약이 연결된 평가 기록도 같은 트랜잭션에서 삭제한다.
        repository.deleteCard(accountId, cardId);
    }

    @Transactional
    public RecordedReview recordReview(String accountId, StudyData.NewReview request) {
        // 같은 카드의 복습은 순서대로 처리해, 동시에 평가해도 앞선 기록을 다음 일정에 반영한다.
        var progress = repository.findProgressForUpdate(accountId, request.cardId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "복습할 카드를 찾지 못했어요."));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var review = new StudyData.Review(request.id(), request.cardId(), request.rating(), now, request.mode(),
                request.firstRecall());
        try {
            repository.insertReview(accountId, review);
        } catch (DuplicateKeyException ignored) {
            // 응답 유실로 같은 평가가 다시 들어오면 복습 횟수를 한 번만 반영한다.
            var existing = repository.getReviewForUpdate(accountId, request.id());
            if (!existing.cardId().equals(request.cardId())
                    || !existing.rating().equals(request.rating()) || !request.mode().equals(existing.mode())
                    || !Objects.equals(existing.firstRecall(), review.firstRecall())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 복습 ID예요.");
            }
            return new RecordedReview(progress, existing);
        }
        // 자유·집중 복습은 통계용 평가 기록을 남기고, 정규 복습에서만 다음 일정을 계산한다.
        if ("scheduled".equals(request.mode())) {
            var history = repository.findRecentScheduledReviews(accountId, review, ReviewScheduler.HISTORY_LIMIT);
            progress = new ReviewProgress(progress.id(), ReviewScheduler.nextDueAt(history.reversed(), review),
                    progress.repetitions() + 1);
            repository.updateSchedule(accountId, progress);
        }
        return new RecordedReview(progress, review);
    }

    @Transactional
    public void importGuest(String accountId, StudyData guest) {
        try {
            repository.insertDecks(accountId, guest.decks());
            repository.insertCards(accountId, guest.cards());
            var reviews = guest.logs().stream()
                    .filter(log -> !("seed".equals(log.cardId()) && log.id().startsWith("seed-")))
                    .toList();
            repository.importReviews(accountId, reviews);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카드가 속한 덱과 복습 기록이 참조하는 카드를 확인해 주세요.", error);
        }
    }

    public record Library(List<StudyData.Deck> decks, List<StudyData.Card> cards, List<StudyData.Review> latestReviews) {}
    public record RecordedReview(ReviewProgress progress, StudyData.Review review) {}
}
