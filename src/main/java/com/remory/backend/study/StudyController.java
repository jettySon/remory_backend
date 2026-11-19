package com.remory.backend.study;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.remory.backend.account.AccountService;
import com.remory.backend.study.StudyData.NewCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/study")
public class StudyController {

    private final AccountService accounts;
    private final StudyService study;
    private final StudyStatistics statistics;

    public StudyController(AccountService accounts, StudyService study, StudyStatistics statistics) {
        this.accounts = accounts;
        this.study = study;
        this.statistics = statistics;
    }

    @GetMapping
    ResponseEntity<StudyService.Library> read(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        return noStore(study.read(accounts.authenticate(token).id()));
    }

    @PostMapping("/decks")
    ResponseEntity<StudyData.Deck> createDeck(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody StudyData.Deck deck) {
        return noStore(study.createDeck(accounts.authenticate(token).id(), deck));
    }

    @PatchMapping("/decks/{deckId}")
    ResponseEntity<StudyData.Deck> editDeck(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable @Size(max = 128) String deckId,
            @Valid @RequestBody StudyData.DeckContent content) {
        return noStore(study.editDeck(accounts.authenticate(token).id(), deckId, content));
    }

    @DeleteMapping("/decks/{deckId}")
    ResponseEntity<Void> deleteDeck(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable @Size(max = 128) String deckId) {
        study.deleteDeck(accounts.authenticate(token).id(), deckId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/decks/{deckId}/cards")
    ResponseEntity<List<StudyData.Card>> createCards(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable @Size(max = 128) String deckId,
            @Valid @RequestBody CardBatch batch) {
        return noStore(study.createCards(accounts.authenticate(token).id(), deckId, batch.cards()));
    }

    @PatchMapping("/cards/{cardId}")
    ResponseEntity<StudyData.Card> editCard(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable @Size(max = 128) String cardId,
            @Valid @RequestBody StudyData.CardContent content) {
        return noStore(study.editCard(accounts.authenticate(token).id(), cardId, content));
    }

    @DeleteMapping("/cards/{cardId}")
    ResponseEntity<Void> deleteCard(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable @Size(max = 128) String cardId) {
        study.deleteCard(accounts.authenticate(token).id(), cardId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/reviews")
    ResponseEntity<StudyService.RecordedReview> recordReview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody StudyData.NewReview request) {
        return noStore(study.recordReview(accounts.authenticate(token).id(), request));
    }

    @PostMapping("/import")
    ResponseEntity<Void> importGuest(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody StudyData guest) {
        study.importGuest(accounts.authenticate(token).id(), guest);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/activity")
    ResponseEntity<Map<LocalDate, StudyReport.Counts>> activity(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String zone) {
        return noStore(statistics.activity(accounts.authenticate(token).id(), from, to, zone));
    }

    @GetMapping("/stats")
    ResponseEntity<StudyReport> stats(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestParam(defaultValue = "all") String period,
            @RequestParam(required = false) @Size(max = 128) String deckId,
            @RequestParam String zone) {
        return noStore(statistics.report(accounts.authenticate(token).id(), period, deckId, zone));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record CardBatch(@NotNull @Size(min = 1, max = 20000) List<@NotNull @Valid NewCard> cards) {}
}
