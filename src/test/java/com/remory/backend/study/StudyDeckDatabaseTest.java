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
