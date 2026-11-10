package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(
    properties = {
      "app.web-recommendations.enabled=true",
      "spring.datasource.url=jdbc:h2:mem:web-needs;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
class WebRecommendationApiTest {
  static final String BASE = "/api/experimental/web-recommendations";
  static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
  @Autowired AuthService auth;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired WebRecommendationService service;
  @Autowired WebRecommendationStore store;
  @Autowired TestClock clock;

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock webClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    volatile Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void reset() {
    clock.now = NOW;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Request request(UUID view, long generation) {
    return new Request(
        view,
        generation,
        "가상 서울숲",
        Set.of(Need.PET_FRIENDLY, Need.INDOOR_PET_FRIENDLY, Need.PARKING),
        false);
  }

  RequestActor actor(AuthService.Tokens tokens) {
    return new RequestActor(auth.authenticate(tokens.accessToken()).subject(), false);
  }

  String start(String token, String key, Request request) throws Exception {
    return http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.result.mode").value("MOCK"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void tokenSearchAndDirectFeedbackStoreOnlyUserOwnedFields() throws Exception {
    var tokens = auth.issue();
    var result = json.readTree(start(tokens.accessToken(), key(), request(UUID.randomUUID(), 1)));
    String id = result.path("requestId").asText();
    assertThat(result.at("/result/candidates").size()).isEqualTo(3);
    var feedback = new Feedback(1, UserFit.MATCH, Set.of(FeedbackReason.NEEDS_MET));
    http.perform(
            put(BASE + "/" + id + "/feedback")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(feedback)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feedback.fit").value("MATCH"));
    String saved =
        jdbc.queryForObject(
            "SELECT request_json FROM web_recommendation_requests WHERE id=?", String.class, id);
    String userFeedback =
        jdbc.queryForObject(
            "SELECT feedback_json FROM web_recommendation_requests WHERE id=?", String.class, id);
    assertThat(saved + userFeedback)
        .doesNotContain("가상 웹추천 식당", "merchant.example", "identitySourceUrl", "assessments");
    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates").doesNotExist());
  }

  @Test
  void ownerIsolationAndTokenRefreshProtectEveryOperation() throws Exception {
    var a = auth.issue();
    var b = auth.issue();
    String id =
        json.readTree(start(a.accessToken(), key(), request(UUID.randomUUID(), 1)))
            .path("requestId")
            .asText();
    http.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
    http.perform(get(BASE + "/" + id).header("X-User", "other"))
        .andExpect(status().isUnauthorized());
    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + b.accessToken()))
        .andExpect(status().isNotFound());
    http.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + b.accessToken()))
        .andExpect(status().isNotFound());
    var refreshed = auth.refresh(a.refreshToken(), key());
    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + refreshed.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void duplicateKeyCannotRequeryAndNewGenerationInvalidatesOldFeedback() throws Exception {
    var token = auth.issue().accessToken();
    var view = UUID.randomUUID();
    var request = request(view, 1);
    String key = key();
    var first = json.readTree(start(token, key, request));
    String id = first.path("requestId").asText();
    var repeated = json.readTree(start(token, key, request));
    assertThat(repeated.path("requestId").asText()).isEqualTo(id);
    assertThat(repeated.at("/result/state").asText()).isEqualTo("ALREADY_REQUESTED");
    assertThat(repeated.at("/result/candidates").size()).isZero();
    start(token, key(), request(view, 2));
    http.perform(
            put(BASE + "/" + id + "/feedback")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new Feedback(1, UserFit.MATCH, Set.of()))))
        .andExpect(status().isConflict());
    http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
        .andExpect(status().isConflict());
  }

  @Test
  void expiryRestartAndDeleteNeverRestoreProviderResultsOrReexecuteDeletedKey() {
    var actor = actor(auth.issue());
    var request = request(UUID.randomUUID(), 1);
    String key = key();
    String id = service.start(actor, key, request).requestId();
    clock.now = NOW.plusSeconds(300);
    assertThatThrownBy(() -> service.feedback(actor, id, new Feedback(1, UserFit.MATCH, Set.of())))
        .isInstanceOf(ApiError.class);
    clock.now = NOW;
    var restarted = new WebRecommendationService(store, clock);
    assertThat(restarted.start(actor, key, request).result().state())
        .isEqualTo(State.ALREADY_REQUESTED);
    assertThatThrownBy(
            () -> restarted.feedback(actor, id, new Feedback(1, UserFit.MATCH, Set.of())))
        .isInstanceOf(ApiError.class);
    assertThat(restarted.delete(actor, id).request()).isNull();
    assertThat(restarted.delete(actor, id).deleted()).isTrue();
    assertThatThrownBy(() -> restarted.start(actor, key, request))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.code).isEqualTo("REQUEST_DELETED"));
  }

  @Test
  void cancellationHidesLateResponseAndIoRunsOutsideTransaction() throws Exception {
    var actor = actor(auth.issue());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var custom =
        new WebRecommendationService(
            store,
            clock,
            (r, deadline, current) -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return new MockWebRecommendationGateway(clock).search(r, deadline, () -> true);
            });
    var request = request(UUID.randomUUID(), 1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var result = pool.submit(() -> custom.start(actor, "late-" + key(), request));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      String id =
          jdbc.queryForObject(
              "SELECT id FROM web_recommendation_requests WHERE view_id=?",
              String.class,
              request.viewId().toString());
      custom.close(actor, id);
      release.countDown();
      assertThat(result.get(5, TimeUnit.SECONDS).result().state()).isEqualTo(State.CANCELLED);
    } finally {
      release.countDown();
    }
  }

  @Test
  void emptyResultsAndFabricatedOrdinalCannotBecomeDirectFeedback() {
    var actor = actor(auth.issue());
    var custom =
        new WebRecommendationService(store, clock, (r, d, c) -> empty(Mode.MOCK, State.EMPTY));
    String id = custom.start(actor, key(), request(UUID.randomUUID(), 1)).requestId();
    assertThatThrownBy(() -> custom.feedback(actor, id, new Feedback(1, UserFit.MATCH, Set.of())))
        .isInstanceOf(ApiError.class);
  }

  @Test
  void cancellationDoesNotRefundAnInFlightSlotBeforeGatewayActuallyReturns() throws Exception {
    var actor = actor(auth.issue());
    var view = UUID.randomUUID();
    var entered = new Semaphore(0);
    var release = new CountDownLatch(1);
    var custom =
        new WebRecommendationService(
            store,
            clock,
            (r, deadline, current) -> {
              entered.release();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return new MockWebRecommendationGateway(clock).search(r, deadline, () -> true);
            });
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<Response>>();
      for (int generation = 1; generation <= 4; generation++) {
        Request input = request(view, generation);
        futures.add(pool.submit(() -> custom.start(actor, key(), input)));
        assertThat(entered.tryAcquire(3, TimeUnit.SECONDS)).isTrue();
      }
      String id =
          jdbc.queryForObject(
              "SELECT id FROM web_recommendation_requests WHERE view_id=? AND generation=4",
              String.class,
              view.toString());
      custom.close(actor, id);
      assertThatThrownBy(() -> custom.start(actor, key(), request(view, 5)))
          .isInstanceOfSatisfying(
              ApiError.class, e -> assertThat(e.code).isEqualTo("WEB_RECOMMENDATION_LIMIT"));
      release.countDown();
      for (var future : futures)
        assertThat(future.get(5, TimeUnit.SECONDS).result().candidates()).isEmpty();
      assertThat(custom.start(actor, key(), request(view, 5)).result().state())
          .isEqualTo(State.RESULTS);
    } finally {
      release.countDown();
    }
  }

  @Test
  void recordedSelectionAcceptsLaterUserOpinionWithoutRestoringMerchantFacts() {
    var actor = actor(auth.issue());
    String id = service.start(actor, key(), request(UUID.randomUUID(), 1)).requestId();
    service.feedback(actor, id, new Feedback(1, UserFit.MATCH, Set.of()));
    service.close(actor, id);
    clock.now = NOW.plusSeconds(3600);
    var restarted = new WebRecommendationService(store, clock);
    assertThat(
            restarted
                .feedback(
                    actor,
                    id,
                    new Feedback(1, UserFit.PARTIAL, Set.of(FeedbackReason.INDOOR_PREFERRED)))
                .feedback()
                .fit())
        .isEqualTo(UserFit.PARTIAL);
    assertThatThrownBy(
            () -> restarted.feedback(actor, id, new Feedback(2, UserFit.MATCH, Set.of())))
        .isInstanceOf(ApiError.class);
  }

  @Test
  void missingConsentUnknownSourceFieldsAndMissingKeyAreRejected() throws Exception {
    String token = auth.issue().accessToken();
    var body = json.valueToTree(request(UUID.randomUUID(), 1));
    ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("personalizationConsent");
    http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    String id =
        json.readTree(start(token, key(), request(UUID.randomUUID(), 1)))
            .path("requestId")
            .asText();
    http.perform(
            put(BASE + "/" + id + "/feedback")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"selectedOrdinal\":1,\"fit\":\"MATCH\",\"reasons\":[],\"sourceUrl\":\"https://unwanted.example\"}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(UUID.randomUUID(), 1))))
        .andExpect(status().isBadRequest());
  }
}
