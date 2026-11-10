package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "app.place-preview.enabled=true",
      "spring.datasource.url=jdbc:h2:mem:preview-http;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
@Timeout(12)
class PlacePreviewApiTest {
  static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
  static final Principal OWNER = () -> "fictional-owner";
  static final Principal OTHER = () -> "fictional-other";
  static final String BASE = "/api/experimental/place-previews";
  static final String QUERY =
      "{\"generation\":1,\"query\":{\"latitude\":37.544,\"longitude\":127.055,\"radiusMeters\":1000,\"limit\":3}}";
  static final String REQUIREMENTS =
      "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,"
          + "\"requiredFeatures\":[\"parking\"],\"preferredCuisines\":[\"한식\"]}";
  static final String PLANNED_QUERY =
      QUERY.substring(0, QUERY.length() - 1) + ",\"requirements\":" + REQUIREMENTS + "}";
  static final List<Map<String, Object>> EXCHANGES = new ArrayList<>();
  static final PlaceSearchResult.Success EMPTY = new PlaceSearchResult.Success(List.of(), NOW, 0);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired PlacePreviewRegistry registry;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean(name = "placePreviewAdapter")
  PlaceSearchAdapter adapter;

  private final List<UUID> opened = new ArrayList<>();

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock previewTestClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @BeforeEach
  void defaultResult() {
    reset(adapter);
    when(adapter.search(any())).thenReturn(EMPTY);
  }

  @AfterEach
  void closeSessions() {
    for (UUID id : opened) {
      try {
        registry.close(new PlacePreviewRegistry.Owner(OWNER.getName()), id);
      } catch (PlacePreviewRegistry.AccessRejected ignored) {
        // A lifecycle test can already have closed it.
      }
    }
  }

  @AfterAll
  static void exportFictionalHttpExchanges() throws Exception {
    Path path = Path.of("build/contract-exchanges/place-preview.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, new ObjectMapper().writeValueAsString(EXCHANGES));
  }

  @Test
  void resultSeparatesDisplayDataAndUnknownBookingFactsWithoutWritingTheDatabase()
      throws Exception {
    List<Long> before = counts();
    when(adapter.search(any()))
        .thenReturn(
            new PlaceSearchResult.Success(List.of(place(PlaceSummary.Provider.MOCK)), NOW, 1));
    UUID id = open();
    JsonNode result = body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200);
    assertThat(result.path("state").asText()).isEqualTo("RESULTS");
    assertThat(result.path("placeSource").asText()).isEqualTo("MOCK");
    assertThat(result.path("reservationMode").asText()).isEqualTo("MOCK");
    assertThat(result.path("fetchedAt").asText()).isEqualTo(NOW.toString());
    assertThat(result.path("expiresAt").asText()).isEqualTo(NOW.plusSeconds(300).toString());
    JsonNode place = result.path("places").get(0);
    for (String field : List.of("parking", "childFriendly", "reservationAvailability"))
      assertThat(place.path(field).asText()).isEqualTo("UNKNOWN");
    assertThat(place.path("bookable").asBoolean()).isFalse();
    assertThat(result.path("recommendationPlan").isNull()).isTrue();
    assertThat(result.toString())
        .doesNotContain("fictional-provider-id", "010-0000-0000", "latitude", "longitude");
    body(send(BASE + "/" + id + "/close", "{}", OWNER), 200);
    body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 404);
    assertThat(counts()).isEqualTo(before);
    verify(adapter, times(1)).search(any());
  }

  @Test
  void explicitConditionsReturnAlignedReasonsUnknownQuestionsAndNoBookingAuthority()
      throws Exception {
    List<Long> before = counts();
    when(adapter.search(any()))
        .thenReturn(
            new MockPlaceSearchAdapter(Clock.fixed(NOW, ZoneOffset.UTC))
                .search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    UUID id = open();
    JsonNode result = body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 200);
    JsonNode plan = result.path("recommendationPlan");
    assertThat(result.path("state").asText()).isEqualTo("RESULTS");
    assertThat(plan.path("state")).isEqualTo(result.path("state"));
    assertThat(plan.path("listingFetchedAt")).isEqualTo(result.path("fetchedAt"));
    assertThat(plan.path("displayValidUntil")).isEqualTo(result.path("expiresAt"));
    assertThat(plan.path("candidates").size()).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      JsonNode candidate = plan.path("candidates").get(i);
      JsonNode display = result.path("places").get(i);
      assertThat(candidate.path("name")).isEqualTo(display.path("name"));
      assertThat(candidate.path("straightLineDistanceMeters"))
          .isEqualTo(display.path("distanceMeters"));
      assertThat(candidate.path("qualification").asText()).isEqualTo("NEEDS_CONFIRMATION");
      assertThat(candidate.path("bookable").asBoolean()).isFalse();
      assertThat(candidate.path("inquiryDispatchAllowed").asBoolean()).isFalse();
      assertThat(candidate.path("reasons")).isNotEmpty();
      assertThat(candidate.path("questions").size()).isEqualTo(4);
      for (JsonNode question : candidate.path("questions")) {
        assertThat(question.path("certainty").asText()).isEqualTo("UNKNOWN");
        boolean arrival = question.path("scope").asText().equals("ARRIVAL");
        assertThat(question.path("arrivalTimeRequired").asBoolean()).isEqualTo(arrival);
      }
    }
    assertThat(result.toString())
        .doesNotContain("providerPlaceId", "phone", "latitude", "longitude", "placeUrl");
    assertThat(counts()).isEqualTo(before);
    verify(adapter, times(1)).search(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"EMPTY", "FILTERED", "FAILED"})
  void plannedEmptyFilteredAndFailureStayDistinct(String state) throws Exception {
    if (state.equals("FILTERED"))
      when(adapter.search(any()))
          .thenReturn(
              new PlaceSearchResult.Success(
                  List.of(
                      new PlaceSummary(
                          PlaceSummary.Provider.MOCK,
                          "outside-fictional",
                          "가상 먼 식당",
                          null,
                          38,
                          127,
                          null,
                          null,
                          null,
                          null)),
                  NOW,
                  0));
    if (state.equals("FAILED"))
      when(adapter.search(any()))
          .thenReturn(new PlaceSearchResult.Failure(PlaceSearchFailure.TIMEOUT));
    UUID id = open();
    JsonNode result = body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 200);
    assertThat(result.path("state").asText()).isEqualTo(state);
    assertThat(result.path("places")).isEmpty();
    if (state.equals("FAILED")) {
      assertThat(result.path("recommendationPlan").isNull()).isTrue();
      assertThat(result.path("failure").asText()).isEqualTo("TIMEOUT");
    } else {
      assertThat(result.path("recommendationPlan").path("state").asText()).isEqualTo(state);
      assertThat(result.path("recommendationPlan").path("candidates")).isEmpty();
    }
    verify(adapter, times(1)).search(any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"party\":null}",
        "{\"party\":{\"adults\":2},\"maxWaitMinutes\":15,\"requiredFeatures\":[],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":0,\"children\":0},\"maxWaitMinutes\":15,\"requiredFeatures\":[],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":121,\"requiredFeatures\":[],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"requiredFeatures\":[],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,\"requiredFeatures\":[\"wheelchair\"],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,\"requiredFeatures\":[null],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,\"requiredFeatures\":[\"parking\",\"parking\"],\"preferredCuisines\":[]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,\"requiredFeatures\":[],\"preferredCuisines\":[\" \"]}",
        "{\"party\":{\"adults\":2,\"children\":1},\"maxWaitMinutes\":15,\"requiredFeatures\":[],\"preferredCuisines\":[\"한식\",\"한식\"]}"
      })
  void invalidRecommendationConditionsNeverDispatch(String requirements) throws Exception {
    UUID id = open();
    String request =
        QUERY.substring(0, QUERY.length() - 1) + ",\"requirements\":" + requirements + "}";
    body(send(BASE + "/" + id + "/queries", request, OWNER), 400);
    verifyNoInteractions(adapter);
  }

  @Test
  void changedConditionsInSameGenerationConflictAndNeverRecomputeOrRedispatch() throws Exception {
    UUID id = open();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return EMPTY;
            });
    try {
      MvcResult pending = send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      MvcResult joined = send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER);
      for (String changed : List.of(PLANNED_QUERY.replace(":15", ":20"), QUERY)) {
        JsonNode conflict = body(send(BASE + "/" + id + "/queries", changed, OWNER), 200);
        assertThat(conflict.path("state").asText()).isEqualTo("REQUEST_CONFLICT");
        assertThat(conflict.path("recommendationPlan").isNull()).isTrue();
      }
      release.countDown();
      assertThat(body(pending, 200)).isEqualTo(body(joined, 200));
      JsonNode replay = body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 200);
      assertThat(replay.path("state").asText()).isEqualTo("ALREADY_FINISHED");
      assertThat(replay.path("recommendationPlan").isNull()).isTrue();
      verify(adapter, times(1)).search(any());
      String next =
          PLANNED_QUERY.replace("\"generation\":1", "\"generation\":2").replace(":15", ":20");
      JsonNode updated = body(send(BASE + "/" + id + "/queries", next, OWNER), 200);
      assertThat(
              updated
                  .path("recommendationPlan")
                  .path("requirements")
                  .path("maxWaitMinutes")
                  .asInt())
          .isEqualTo(20);
      verify(adapter, times(2)).search(any());
    } finally {
      release.countDown();
    }
  }

  @Test
  void screenCloseSuppressesAnInFlightRecommendation() throws Exception {
    UUID id = open();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return EMPTY;
            });
    try {
      MvcResult pending = send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      body(send(BASE + "/" + id + "/close", "{}", OWNER), 200);
      JsonNode closed = body(pending, 200);
      assertThat(closed.path("state").asText()).isEqualTo("CLOSED");
      assertThat(closed.path("places")).isEmpty();
      assertThat(closed.path("recommendationPlan").isNull()).isTrue();
      body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 404);
      verify(adapter, times(1)).search(any());
    } finally {
      release.countDown();
    }
  }

  @Test
  void emptyIsSuccessAndCompletedGenerationIsNotAutomaticallyRepeated() throws Exception {
    UUID id = open();
    assertThat(body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200).path("state").asText())
        .isEqualTo("EMPTY");
    JsonNode replay = body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200);
    assertThat(replay.path("state").asText()).isEqualTo("ALREADY_FINISHED");
    assertThat(replay.path("places")).isEmpty();
    assertThat(replay.path("fetchedAt").isNull()).isTrue();
    verify(adapter, times(1)).search(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Owner", "X-User-Id", "Authorization"})
  void headersCannotCreateAnAuthenticatedOwner(String header) throws Exception {
    var response =
        mvc.perform(
                post(BASE)
                    .header(header, "fictional-owner")
                    .contentType("application/json")
                    .content("{}"))
            .andReturn();
    assertThat(body(response, 401).path("code").asText())
        .isEqualTo("PREVIEW_AUTHENTICATION_REQUIRED");
    verifyNoInteractions(adapter);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "invalid owner", "가상사용자"})
  void invalidPrincipalIsDeniedWithoutEchoingItsValue(String name) throws Exception {
    JsonNode error = body(send(BASE, "{}", () -> name), 401);
    assertThat(error.path("code").asText()).isEqualTo("PREVIEW_AUTHENTICATION_REQUIRED");
    verifyNoInteractions(adapter);
  }

  @Test
  void foreignAndUnknownIdsAreIndistinguishableForAllOperations() throws Exception {
    UUID id = open();
    for (String operation : List.of("queries", "cancel", "close")) {
      String payload =
          operation.equals("queries")
              ? QUERY
              : operation.equals("cancel") ? "{\"generation\":1}" : "{}";
      JsonNode foreign = body(send(BASE + "/" + id + "/" + operation, payload, OTHER), 404);
      JsonNode unknown =
          body(send(BASE + "/" + UUID.randomUUID() + "/" + operation, payload, OWNER), 404);
      assertThat(foreign).isEqualTo(unknown);
    }
    verifyNoInteractions(adapter);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"generation\":null,\"query\":{}}",
        "{\"generation\":0,\"query\":{}}",
        "{\"generation\":1.5,\"query\":{}}",
        "{\"generation\":\"1\",\"query\":{}}",
        "{\"generation\":9007199254740992,\"query\":{}}",
        "{\"generation\":1,\"query\":null}",
        "{\"generation\":1,\"query\":{\"latitude\":91,\"longitude\":127,\"radiusMeters\":1000,\"limit\":3}}",
        "{\"generation\":1,\"query\":{\"latitude\":37,\"longitude\":181,\"radiusMeters\":1000,\"limit\":3}}",
        "{\"generation\":1,\"query\":{\"latitude\":37,\"longitude\":127,\"radiusMeters\":0,\"limit\":3}}",
        "{\"generation\":1,\"query\":{\"latitude\":37,\"longitude\":127,\"radiusMeters\":1000,\"limit\":4}}",
        "{\"generation\":1,\"query\":{\"latitude\":37,\"longitude\":127,\"radiusMeters\":1000.5,\"limit\":3}}",
        "{\"generation\":1,\"query\":{\"latitude\":37,\"longitude\":127,\"radiusMeters\":1000,\"limit\":3},\"owner\":\"forged\"}"
      })
  void invalidQueriesAreRejectedBeforeDispatch(String payload) throws Exception {
    UUID id = open();
    JsonNode error = body(send(BASE + "/" + id + "/queries", payload, OWNER), 400);
    assertThat(error.path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(adapter);
  }

  @Test
  void createRejectsClientOwnerAndMalformedUuidUsesSafeError() throws Exception {
    body(send(BASE, "{\"owner\":\"forged\"}", OWNER), 400);
    body(send(BASE + "/not-a-uuid/queries", QUERY, OWNER), 400);
    verifyNoInteractions(adapter);
  }

  @Test
  void sessionLimitDoesNotDispatchOrSilentlyReplaceExistingSessions() throws Exception {
    open();
    open();
    assertThat(body(send(BASE, "{}", OWNER), 429).path("code").asText())
        .isEqualTo("PREVIEW_SESSION_LIMIT");
    verifyNoInteractions(adapter);
  }

  @Test
  void concurrentSameGenerationJoinsOneCallAndConflictDoesNotDispatch() throws Exception {
    UUID id = open();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return EMPTY;
            });
    try {
      MvcResult first = send(BASE + "/" + id + "/queries", QUERY, OWNER);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      MvcResult joined = send(BASE + "/" + id + "/queries", QUERY, OWNER);
      JsonNode conflict =
          body(send(BASE + "/" + id + "/queries", QUERY.replace("1000", "2000"), OWNER), 200);
      assertThat(conflict.path("state").asText()).isEqualTo("REQUEST_CONFLICT");
      release.countDown();
      assertThat(body(first, 200)).isEqualTo(body(joined, 200));
      verify(adapter, times(1)).search(any());
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellationCompletesWaitingHttpResponseAndFencesDelayedRequests() throws Exception {
    UUID id = open();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return EMPTY;
            });
    try {
      MvcResult pending = send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(
              body(send(BASE + "/" + id + "/cancel", "{\"generation\":2}", OWNER), 200)
                  .path("cancelledWork")
                  .asBoolean())
          .isTrue();
      JsonNode cancelled = body(pending, 200);
      assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
      assertThat(cancelled.path("recommendationPlan").isNull()).isTrue();
      assertThat(
              body(
                      send(
                          BASE + "/" + id + "/queries",
                          QUERY.replace("\"generation\":1", "\"generation\":2"),
                          OWNER),
                      200)
                  .path("state")
                  .asText())
          .isEqualTo("ALREADY_FINISHED");
      verify(adapter, times(1)).search(any());
    } finally {
      release.countDown();
    }
  }

  @Test
  void destinationChangeSuppressesLateResultAndKeepsNewGeneration() throws Exception {
    UUID id = open();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                while (release.getCount() > 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                  }
                }
                return new PlaceSearchResult.Success(
                    List.of(place(PlaceSummary.Provider.MOCK)), NOW, 0);
              }
              return EMPTY;
            });
    try {
      MvcResult old = send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      String next =
          PLANNED_QUERY
              .replace("\"generation\":1", "\"generation\":2")
              .replace("127.055", "127.056");
      MvcResult current = send(BASE + "/" + id + "/queries", next, OWNER);
      JsonNode oldBody = body(old, 200);
      assertThat(oldBody.path("state").asText()).isEqualTo("SUPERSEDED");
      assertThat(oldBody.path("places")).isEmpty();
      assertThat(oldBody.path("recommendationPlan").isNull()).isTrue();
      release.countDown();
      JsonNode currentBody = body(current, 200);
      assertThat(currentBody.path("generation").asLong()).isEqualTo(2);
      assertThat(currentBody.path("state").asText()).isEqualTo("EMPTY");
      assertThat(body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200).path("state").asText())
          .isEqualTo("STALE_GENERATION");
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @EnumSource(PlaceSearchFailure.class)
  void providerFailureIsNotAnEmptySuccess(PlaceSearchFailure failure) throws Exception {
    UUID id = open();
    when(adapter.search(any())).thenReturn(new PlaceSearchResult.Failure(failure));
    JsonNode result = body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200);
    assertThat(result.path("state").asText()).isEqualTo("FAILED");
    assertThat(result.path("failure").asText()).isEqualTo(failure.name());
    assertThat(result.path("fetchedAt").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "count", "future", "expired", "rejected", "null", "exception"})
  void invalidAndUnexpectedAdapterResultsAreSafe(String kind) throws Exception {
    UUID id = open();
    var listing =
        place(kind.equals("source") ? PlaceSummary.Provider.KAKAO : PlaceSummary.Provider.MOCK);
    when(adapter.search(any()))
        .thenReturn(
            new PlaceSearchResult.Success(
                kind.equals("count")
                    ? List.of(listing, listing, listing, listing)
                    : List.of(listing),
                kind.equals("future")
                    ? NOW.plusSeconds(1)
                    : kind.equals("expired") ? NOW.minusSeconds(300) : NOW,
                0));
    if (kind.equals("null")) when(adapter.search(any())).thenReturn(null);
    if (kind.equals("rejected"))
      when(adapter.search(any())).thenReturn(new PlaceSearchResult.Success(List.of(), NOW, 1));
    if (kind.equals("exception"))
      when(adapter.search(any())).thenThrow(new IllegalStateException("private upstream text"));
    JsonNode result = body(send(BASE + "/" + id + "/queries", QUERY, OWNER), 200);
    assertThat(result.path("state").asText()).isEqualTo("FAILED");
    assertThat(result.path("places")).isEmpty();
    assertThat(result.toString()).doesNotContain("private upstream text", "fictional-provider-id");
  }

  @Test
  void requestDeadlineReturnsTimedOutWithoutAnAutomaticRetry() throws Exception {
    UUID id = open();
    CountDownLatch release = new CountDownLatch(1);
    when(adapter.search(any()))
        .thenAnswer(
            invocation -> {
              release.await();
              return EMPTY;
            });
    try {
      JsonNode result = body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 200);
      assertThat(result.path("state").asText()).isEqualTo("TIMED_OUT");
      assertThat(result.path("places")).isEmpty();
      assertThat(result.path("recommendationPlan").isNull()).isTrue();
      assertThat(
              body(send(BASE + "/" + id + "/queries", PLANNED_QUERY, OWNER), 200)
                  .path("state")
                  .asText())
          .isEqualTo("ALREADY_FINISHED");
      verify(adapter, times(1)).search(any());
    } finally {
      release.countDown();
    }
  }

  private UUID open() throws Exception {
    UUID id = UUID.fromString(body(send(BASE, "{}", OWNER), 200).path("previewId").asText());
    opened.add(id);
    return id;
  }

  private MvcResult send(String path, String payload, Principal principal) throws Exception {
    return mvc.perform(
            post(path).principal(principal).contentType("application/json").content(payload))
        .andReturn();
  }

  private JsonNode body(MvcResult pending, int status) throws Exception {
    MvcResult result = pending;
    if (pending.getRequest().isAsyncStarted()) {
      pending.getAsyncResult(4000);
      result = mvc.perform(asyncDispatch(pending)).andReturn();
    }
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
    EXCHANGES.add(
        Map.of(
            "path",
            pending.getRequest().getRequestURI().substring(BASE.length()),
            "request",
            mapper.readTree(pending.getRequest().getContentAsString()),
            "response",
            response,
            "status",
            status));
    return response;
  }

  private List<Long> counts() {
    return List.of(
        jdbc.queryForObject("select count(*) from searches", Long.class),
        jdbc.queryForObject("select count(*) from commands", Long.class),
        jdbc.queryForObject("select count(*) from reservations", Long.class),
        jdbc.queryForObject("select count(*) from calls", Long.class));
  }

  private static PlaceSummary place(PlaceSummary.Provider provider) {
    return new PlaceSummary(
        provider,
        "fictional-provider-id",
        "가상 비교식당",
        "한식",
        37.544,
        127.055,
        "가상 주소",
        "010-0000-0000",
        null,
        100);
  }
}
