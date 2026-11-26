package com.remory.backend.study;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StudyData(
        @NotNull @Size(max = 1000) List<@NotNull @Valid Deck> decks,
        @NotNull @Size(max = 20000) List<@NotNull @Valid Card> cards,
        @NotNull @Size(max = 100000) List<@NotNull @Valid Review> logs
) {

    private static final String DECK_THEME = "peach|lavender|butter|blue|sage|fog";

    public record Deck(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 35) String title,
            @NotNull @Size(max = 70) String subtitle,
            @NotNull @Pattern(regexp = DECK_THEME) String theme,
            @NotBlank @Size(max = 32) String icon
    ) {}

    public record DeckContent(
            @NotBlank @Size(max = 35) String title,
            @NotNull @Size(max = 70) String subtitle,
            @NotNull @Pattern(regexp = DECK_THEME) String theme
    ) {}

    private static final int MAX_EXPLANATION_LENGTH = 240;

    public record Card(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String deckId,
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @NotNull Instant dueAt,
            @NotNull @PositiveOrZero Integer repetitions,
            @Pattern(regexp = "basic|word|cloze|choice") String kind,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = MAX_EXPLANATION_LENGTH) String explanation
    ) {
        public Card {
            if (explanation != null) {
                explanation = explanation.strip();
                if (explanation.isEmpty()) explanation = null;
            }
        }
    }

    public record NewCard(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @Pattern(regexp = "basic|word|cloze|choice") String kind,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices,
            @Size(max = MAX_EXPLANATION_LENGTH) String explanation
    ) {}

    public record CardContent(
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices,
            @Size(max = MAX_EXPLANATION_LENGTH) String explanation
    ) {}

    private static final String FIRST_RECALL = "self_recalled|self_missed|choice_correct|choice_incorrect|choice_revealed";

    public record ReviewProgress(String id, Instant dueAt, int repetitions) {}

    public record NewReview(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String cardId,
            @NotNull @Pattern(regexp = "again|hard|good|easy") String rating,
            @NotNull @Pattern(regexp = "scheduled|focus|free") String mode,
            @Pattern(regexp = FIRST_RECALL) String firstRecall
    ) {}

    public record Review(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String cardId,
            @NotNull @Pattern(regexp = "again|hard|good|easy") String rating,
            @NotNull Instant date,
            @Pattern(regexp = "scheduled|focus|free") String mode,
            @Pattern(regexp = FIRST_RECALL) String firstRecall
    ) {
        public Review {
            // Import와 신규 저장 모두 정규 복습에서만 첫 시도 측정값을 보관한다.
            if (!"scheduled".equals(mode)) firstRecall = null;
        }
    }
}
