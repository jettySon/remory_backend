package com.remory.backend.study;

import java.time.Instant;
import java.util.List;

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

    public record Deck(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 35) String title,
            @NotNull @Size(max = 70) String subtitle,
            @NotNull @Pattern(regexp = "peach|lavender|butter|blue") String theme,
            @NotBlank @Size(max = 32) String icon
    ) {}

    public record DeckContent(
            @NotBlank @Size(max = 35) String title,
            @NotNull @Size(max = 70) String subtitle,
            @NotNull @Pattern(regexp = "peach|lavender|butter|blue") String theme
    ) {}

    public record Card(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String deckId,
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @NotNull Instant dueAt,
            @NotNull @PositiveOrZero Integer repetitions,
            @Pattern(regexp = "basic|word|cloze|choice") String kind,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices
    ) {}

    public record NewCard(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @Pattern(regexp = "basic|word|cloze|choice") String kind,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices
    ) {}

    public record CardContent(
            @NotBlank @Size(max = 20000) String front,
            @NotBlank @Size(max = 20000) String back,
            @Size(max = 3) List<@NotBlank @Size(max = 20000) String> choices
    ) {}

    public record ReviewProgress(String id, Instant dueAt, int repetitions) {}

    public record NewReview(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String cardId,
            @NotNull @Pattern(regexp = "again|hard|good|easy") String rating,
            @NotNull @Pattern(regexp = "scheduled|focus|free") String mode
    ) {}

    public record Review(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 128) String cardId,
            @NotNull @Pattern(regexp = "again|hard|good|easy") String rating,
            @NotNull Instant date,
            @Pattern(regexp = "scheduled|focus|free") String mode
    ) {}
}
