package com.remory.backend.study;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remory.backend.account.GoogleIdentityVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=${REMORY_TEST_JDBC_URL}",
        "spring.datasource.username=${REMORY_TEST_DB_USER:remory}",
        "spring.datasource.password=${REMORY_TEST_DB_PASSWORD:remory-local}"
})
@AutoConfigureMockMvc
@ActiveProfiles("session-integration")
@EnabledIfEnvironmentVariable(named = "REMORY_TEST_JDBC_URL", matches = "jdbc:mysql:.*")
class StudyDeckDatabaseTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockitoBean GoogleIdentityVerifier google;
    private final List<String> accounts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        accounts.forEach(id -> jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", id).update());
    }

    @Test
    void editingDeckPreservesCardsScheduleReviewsAndIdentity() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        JsonNode cardsBefore = library(user).get("cards");
        var logsBefore = logs(user);
        JsonNode edited = call(patch("/api/v1/study/decks/deck"), user,
                Map.of("title", "  New title  ", "subtitle", "New description", "theme", "lavender"), 200);
        assertThat(edited.get("id").asText()).isEqualTo("deck");
        assertThat(edited.get("icon").asText()).isEqualTo("book");
        assertThat(edited.get("title").asText()).isEqualTo("New title");
        assertThat(edited.get("subtitle").asText()).isEqualTo("New description");
        assertThat(edited.get("theme").asText()).isEqualTo("lavender");
        assertThat(library(user).get("cards")).isEqualTo(cardsBefore);
        assertThat(logs(user)).isEqualTo(logsBefore).hasSize(1);
    }

    @Test
    void createsEditsAndImportsDecksWithTheNewColors() throws Exception {
        User user = signup();
        var created = call(post("/api/v1/study/decks"), user,
                Map.of("id", "new-deck", "title", "New deck", "subtitle", "", "theme", "sage", "icon", "book"), 200);
        assertThat(created.path("theme").asText()).isEqualTo("sage");

        var edited = call(patch("/api/v1/study/decks/new-deck"), user,
                Map.of("title", "New deck", "subtitle", "", "theme", "fog"), 200);
        assertThat(edited.path("theme").asText()).isEqualTo("fog");

        var guestDecks = List.of(
                Map.of("id", "guest-sage", "title", "Guest sage", "subtitle", "", "theme", "sage", "icon", "code"),
                Map.of("id", "guest-fog", "title", "Guest fog", "subtitle", "", "theme", "fog", "icon", "book"));
        call(post("/api/v1/study/import"), user, Map.of("decks", guestDecks, "cards", List.of(), "logs", List.of()), 204);

        var decks = library(user).get("decks");
        assertThat(decks).extracting(deck -> deck.path("theme").asText())
                .containsExactly("fog", "sage", "fog");
    }

    @Test
    void deletingDeckCascadesItsCardsAndReviewsAndIsSafeToRetry() throws Exception {
        User user = signup();
        seed(user, "remove", "remove-card");
        seed(user, "keep", "keep-card");
        call(delete("/api/v1/study/decks/remove"), user, null, 204);
        call(delete("/api/v1/study/decks/remove"), user, null, 204);
        JsonNode remaining = library(user);
        assertThat(remaining.get("decks")).hasSize(1);
        assertThat(remaining.get("decks").get(0).get("id").asText()).isEqualTo("keep");
        assertThat(remaining.get("cards")).hasSize(1);
        assertThat(remaining.get("cards").get(0).get("id").asText()).isEqualTo("keep-card");
        assertThat(logs(user)).hasSize(1).allMatch(log -> log.contains("keep-card"));
        JsonNode stats = call(get("/api/v1/study/stats?period=all&zone=Asia/Seoul"), user, null, 200);
        assertThat(stats.get("totals").get("total").asInt()).isOne();
    }

    @Test
    void accountScopeProtectsOtherUsersEvenWhenDeckIdsMatch() throws Exception {
        User owner = signup();
        User other = signup();
        seed(owner, "shared-id", "shared-card-id");
        seed(other, "shared-id", "shared-card-id");
        JsonNode otherBefore = library(other);
        var otherLogs = logs(other);
        call(patch("/api/v1/study/decks/shared-id"), owner,
                Map.of("title", "Only mine", "subtitle", "", "theme", "peach"), 200);
        assertThat(library(other)).isEqualTo(otherBefore);
        call(delete("/api/v1/study/decks/shared-id"), owner, null, 204);
        assertThat(library(other)).isEqualTo(otherBefore);
        assertThat(logs(other)).isEqualTo(otherLogs);
        call(patch("/api/v1/study/decks/shared-id"), owner,
                Map.of("title", "Cannot reach other", "subtitle", "", "theme", "blue"), 404);
    }

    @Test
    void invalidOrMissingEditsDoNotChangeStoredDeck() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        JsonNode before = library(user);
        for (Map<String, String> invalid : List.of(
                Map.of("title", "  ", "subtitle", "", "theme", "blue"),
                Map.of("title", "x".repeat(36), "subtitle", "", "theme", "blue"),
                Map.of("title", "Title", "subtitle", "x".repeat(71), "theme", "blue"),
                Map.of("title", "Title", "subtitle", "", "theme", "unknown"),
                Map.of("title", "Title", "theme", "blue"))) {
            call(patch("/api/v1/study/decks/deck"), user, invalid, 400);
        }
        call(patch("/api/v1/study/decks/missing"), user,
                Map.of("title", "Title", "subtitle", "", "theme", "blue"), 404);
        assertThat(library(user)).isEqualTo(before);
    }

    @Test
    void deckMutationsRequireAuthentication() throws Exception {
        mvc.perform(patch("/api/v1/study/decks/deck").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("title", "Title", "subtitle", "", "theme", "blue"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/study/decks/deck")).andExpect(status().isUnauthorized());
    }

    @Test
    void libraryIncludesLatestReviewPerCardAcrossAllModesAndAccountScope() throws Exception {
        User owner = signup();
        User other = signup();
        seed(owner, "deck", "shared-card");
        seed(other, "deck", "shared-card");
        var free = call(post("/api/v1/study/reviews"), owner,
                Map.of("id", "free-review", "cardId", "shared-card", "rating", "hard", "mode", "free"), 200);
        assertThat(library(owner).get("latestReviews")).hasSize(1);
        assertThat(library(owner).get("latestReviews").get(0)).isEqualTo(free.get("review"));
        assertThat(library(other).get("latestReviews").get(0).get("id").asText()).isEqualTo("review-shared-card");
        var focus = call(post("/api/v1/study/reviews"), owner,
                Map.of("id", "focus-review", "cardId", "shared-card", "rating", "easy", "mode", "focus"), 200);
        assertThat(library(owner).get("latestReviews").get(0)).isEqualTo(focus.get("review"));
        call(delete("/api/v1/study/cards/shared-card"), owner, null, 204);
        assertThat(library(owner).get("latestReviews")).isEmpty();
        assertThat(library(other).get("latestReviews")).hasSize(1);
    }

    @Test
    void importedHistoryUsesReviewTimestampAndStableIdOrderInsteadOfInsertOrder() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        var reviews = List.of(
                Map.of("id", "z-new", "cardId", "card", "date", "2090-01-03T12:00:00Z", "rating", "easy", "mode", "free"),
                Map.of("id", "a-tied", "cardId", "card", "date", "2090-01-03T12:00:00Z", "rating", "hard", "mode", "focus"),
                Map.of("id", "last-inserted", "cardId", "card", "date", "2000-01-01T12:00:00Z", "rating", "again", "mode", "scheduled"));
        call(post("/api/v1/study/import"), user, Map.of("decks", List.of(), "cards", List.of(), "logs", reviews), 204);
        JsonNode latest = library(user).get("latestReviews");
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).get("id").asText()).isEqualTo("z-new");
        assertThat(latest.get(0).get("rating").asText()).isEqualTo("easy");
        assertThat(latest.get(0).get("date").asText()).isEqualTo("2090-01-03T12:00:00Z");
    }

    @Test
    void unstudiedCardsHaveNoLastReviewUntilTheirFirstFreeReviewWithoutChangingSchedule() throws Exception {
        User user = signup();
        call(post("/api/v1/study/decks"), user,
                Map.of("id", "deck", "title", "deck", "subtitle", "", "theme", "blue", "icon", "book"), 200);
        call(post("/api/v1/study/decks/deck/cards"), user,
                Map.of("cards", List.of(Map.of("id", "card", "front", "Question", "back", "Answer", "kind", "basic"))), 200);
        JsonNode before = library(user);
        assertThat(before.get("latestReviews")).isEmpty();
        var saved = call(post("/api/v1/study/reviews"), user,
                Map.of("id", "first-free", "cardId", "card", "rating", "again", "mode", "free"), 200);
        JsonNode after = library(user);
        assertThat(after.get("cards")).isEqualTo(before.get("cards"));
        assertThat(after.get("latestReviews")).hasSize(1);
        assertThat(after.get("latestReviews").get(0)).isEqualTo(saved.get("review"));
    }

    @Test
    void savesFirstRecallFromNewAndImportedReviewsAndKeepsRetriesIdempotent() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        assertThat(library(user).get("latestReviews").get(0).path("firstRecall").isNull()).isTrue();

        var imported = Map.of("id", "imported", "cardId", "card", "rating", "good", "mode", "scheduled",
                "date", "2020-11-20T12:00:00Z", "firstRecall", "self_recalled");
        call(post("/api/v1/study/import"), user, Map.of("decks", List.of(), "cards", List.of(), "logs", List.of(imported)), 204);
        String importedRecall = jdbc.sql("SELECT first_recall FROM review_logs WHERE account_id = :accountId AND id = 'imported'")
                .param("accountId", user.id()).query(String.class).single();
        assertThat(importedRecall).isEqualTo("self_recalled");

        var request = Map.of("id", "measured", "cardId", "card", "rating", "again", "mode", "scheduled",
                "firstRecall", "self_missed");
        var recorded = call(post("/api/v1/study/reviews"), user, request, 200);
        assertThat(recorded.get("review").get("firstRecall").asText()).isEqualTo("self_missed");
        assertThat(library(user).get("latestReviews").get(0)).isEqualTo(recorded.get("review"));
        assertThat(call(post("/api/v1/study/reviews"), user, request, 200)).isEqualTo(recorded);
        assertThat(logs(user)).hasSize(3);
        var changed = new java.util.HashMap<>(request);
        changed.put("firstRecall", "self_recalled");
        call(post("/api/v1/study/reviews"), user, changed, 409);
    }

    @Test
    void editsAndClearsOptionalCardExplanationWithoutChangingReviews() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        var before = library(user).get("cards").get(0);
        var logsBefore = logs(user);
        assertThat(before.has("explanation")).isFalse();

        var explanation = "final 변수는 한 번만 대입할 수 있어요.";
        var edited = call(patch("/api/v1/study/cards/card"), user,
                Map.of("front", "Question", "back", "Answer", "explanation", "  " + explanation + "  "), 200);
        assertThat(edited.path("explanation").asText()).isEqualTo(explanation);
        assertThat(edited.get("dueAt")).isEqualTo(before.get("dueAt"));
        assertThat(edited.get("repetitions")).isEqualTo(before.get("repetitions"));
        assertThat(library(user).get("cards").get(0)).isEqualTo(edited);

        call(patch("/api/v1/study/cards/card"), user, Map.of("front", "Question", "back", "Answer"), 200);
        assertThat(library(user).get("cards").get(0)).isEqualTo(edited);
        call(patch("/api/v1/study/cards/card"), user,
                Map.of("front", "Question", "back", "Answer", "explanation", "가".repeat(241)), 400);
        assertThat(library(user).get("cards").get(0)).isEqualTo(edited);
        call(patch("/api/v1/study/cards/card"), user,
                Map.of("front", "Question", "back", "Answer", "explanation", ""), 200);
        assertThat(library(user).get("cards").get(0)).isEqualTo(before);
        assertThat(logs(user)).isEqualTo(logsBefore);
    }

    @Test
    void savesNewAndGuestImportedCardExplanationsInTheAccountLibrary() throws Exception {
        User user = signup();
        seed(user, "deck", "card");
        var created = call(post("/api/v1/study/decks/deck/cards"), user, Map.of("cards", List.of(
                Map.of("id", "new", "front", "Why?", "back", "Answer", "explanation", "A short reason."))), 200);
        assertThat(created.get(0).path("explanation").asText()).isEqualTo("A short reason.");

        var imported = Map.of("id", "guest", "deckId", "deck", "front", "Why?", "back", "Answer",
                "explanation", "Guest explanation.", "dueAt", "2030-11-25T10:30:00Z", "repetitions", 0);
        call(post("/api/v1/study/import"), user,
                Map.of("decks", List.of(), "cards", List.of(imported), "logs", List.of()), 204);
        JsonNode cards = library(user).get("cards");
        assertThat(cards).hasSize(3);
        assertThat(cards.get(1)).isEqualTo(created.get(0));
        assertThat(cards.get(2).path("explanation").asText()).isEqualTo("Guest explanation.");
    }

    private User signup() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", UUID.randomUUID() + "@example.com", "password", "test-password"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode session = json.readTree(result.getResponse().getContentAsString());
        String id = session.get("account").get("id").asText();
        accounts.add(id);
        return new User(id, "Bearer " + session.get("accessToken").asText());
    }

    private void seed(User user, String deckId, String cardId) throws Exception {
        call(post("/api/v1/study/decks"), user,
                Map.of("id", deckId, "title", deckId, "subtitle", "", "theme", "blue", "icon", "book"), 200);
        call(post("/api/v1/study/decks/" + deckId + "/cards"), user,
                Map.of("cards", List.of(Map.of("id", cardId, "front", "Question", "back", "Answer", "kind", "basic"))), 200);
        call(post("/api/v1/study/reviews"), user,
                Map.of("id", "review-" + cardId, "cardId", cardId, "rating", "good", "mode", "scheduled"), 200);
    }

    private JsonNode library(User user) throws Exception { return call(get("/api/v1/study"), user, null, 200); }

    private List<String> logs(User user) {
        return jdbc.sql("SELECT id,card_id,rating,reviewed_at,mode FROM review_logs WHERE account_id = :id ORDER BY id")
                .param("id", user.id()).query((row, index) -> row.getString(1) + "|" + row.getString(2) + "|"
                        + row.getString(3) + "|" + row.getString(4) + "|" + row.getString(5)).list();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, User user, Object body, int expected) throws Exception {
        request.header("Authorization", user.authorization());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andExpect(status().is(expected));
        if (expected < 400) response.andExpect(header().string("Cache-Control", "no-store"));
        var result = response.andReturn();
        return result.getResponse().getContentAsByteArray().length == 0 ? null : json.readTree(result.getResponse().getContentAsString());
    }

    private record User(String id, String authorization) {}
}
