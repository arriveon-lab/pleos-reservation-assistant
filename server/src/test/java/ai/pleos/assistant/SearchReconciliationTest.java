package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reconcile;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SearchReconciliationTest {
  static final Instant START = Instant.parse("2026-11-10T06:00:00Z");
  @Autowired WorkflowService service;
  @Autowired TestClock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactions;
  RequestActor actor;

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock reconciliationClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    volatile Instant now = START;

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
    clock.now = START;
    actor = new RequestActor(UUID.randomUUID().toString(), false);
  }

  SearchRequest request(Instant eta, Instant observed) {
    return new SearchRequest(
        new Navigation(
            "fictional-trip",
            new Destination("가상 목적지", 37.544, 127.055),
            eta,
            observed,
            NavigationSource.MOCK),
        new Party(2, 1),
        15,
        Set.of("parking"),
        Set.of("한식"),
        false);
  }

  SearchRequest fresh() {
    return request(START.plusSeconds(3600), START);
  }

  void fails(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiError.class, error -> assertThat(error.code).isEqualTo(code));
  }

  long searches() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM searches", Long.class);
  }

  @Test
  void uncommittedClosureRollsBackAndExactRetryCanResolve() {
    var body = request(START.minusSeconds(1), START);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          service.reconcileSearch(actor, "rollback", body);
                          throw new IllegalStateException("SIMULATED_WRITE_FAILURE");
                        }))
        .isInstanceOf(IllegalStateException.class);
    fails("INVALID_TIME", () -> service.search(actor, "rollback", body));
    assertThat(service.reconcileSearch(actor, "rollback", body).state())
        .isEqualTo("REJECTED_CLOSED");
    fails("SEARCH_COMMAND_CLOSED", () -> service.search(actor, "rollback", body));
  }

  @Test
  void rejectedExpiredRequestClosesWithoutSearchingAndCannotRevive() {
    var body = request(START.minusSeconds(1), START.minusSeconds(3601));
    fails("INVALID_TIME", () -> service.search(actor, "old", body));
    long before = searches();
    var result = service.reconcileSearch(actor, "old", body);
    assertThat(result.state()).isEqualTo("REJECTED_CLOSED");
    assertThat(result.reason()).isEqualTo("INVALID_TIME");
    assertThat(result.searchId()).isNull();
    assertThat(searches()).isEqualTo(before);
    assertThat(service.reconcileSearch(actor, "old", body)).isEqualTo(result);
    clock.now = START.minusSeconds(3600);
    fails("SEARCH_COMMAND_CLOSED", () -> service.search(actor, "old", body));
  }

  @Test
  void staleNavigationClosesAndNewExplicitSearchUsesNewKeySameJourney() {
    var body = request(START.plusSeconds(3600), START.minusSeconds(301));
    fails("STALE_NAVIGATION", () -> service.search(actor, "stale", body));
    assertThat(service.reconcileSearch(actor, "stale", body).reason())
        .isEqualTo("STALE_NAVIGATION");
    fails("SEARCH_COMMAND_CLOSED", () -> service.search(actor, "stale", body));
    var search = service.search(actor, "explicit-new", fresh());
    assertThat(search.request.navigation().tripId()).isEqualTo(body.navigation().tripId());
  }

  @Test
  void completedSearchWinsEvenWhenOriginalRequestHasExpired() {
    var body = fresh();
    var search = service.search(actor, "lost-response", body);
    clock.now = START.plusSeconds(7200);
    long before = searches();
    var result = service.reconcileSearch(actor, "lost-response", body);
    assertThat(result.state()).isEqualTo("COMPLETED");
    assertThat(result.searchId()).isEqualTo(search.id);
    assertThat(result.reason()).isNull();
    assertThat(service.search(actor, "lost-response", body).id).isEqualTo(search.id);
    assertThat(searches()).isEqualTo(before);
  }

  @Test
  void differentPayloadCannotCloseOrReplayEitherCommandScope() {
    var body = request(START.minusSeconds(1), START);
    service.reconcileSearch(actor, "closed", body);
    fails("KEY_REUSED", () -> service.reconcileSearch(actor, "closed", fresh()));
    fails("KEY_REUSED", () -> service.search(actor, "closed", fresh()));
    service.search(actor, "completed", fresh());
    fails("KEY_REUSED", () -> service.reconcileSearch(actor, "completed", body));
    assertThat(service.reconcileSearch(actor, "completed", fresh()).state()).isEqualTo("COMPLETED");
  }

  @Test
  void ownerIsolatedAndNoDisclosureFromSharedKey() {
    var body = fresh();
    var search = service.search(actor, "shared", body);
    var other = new RequestActor(UUID.randomUUID().toString(), false);
    clock.now = START.plusSeconds(7200);
    var result = service.reconcileSearch(other, "shared", body);
    assertThat(result.state()).isEqualTo("REJECTED_CLOSED");
    assertThat(result.searchId()).isNull();
    assertThat(service.reconcileSearch(actor, "shared", body).searchId()).isEqualTo(search.id);
  }

  @Test
  void validAndFutureInvalidInputCannotBeClosedAsOld() {
    long before = searches();
    for (var body :
        List.of(
            fresh(),
            request(START.plusSeconds(86401), START),
            request(START.plusSeconds(3600), START.plusSeconds(6)))) {
      fails("REQUEST_NOT_CLOSABLE", () -> service.reconcileSearch(actor, "valid", body));
    }
    assertThat(searches()).isEqualTo(before);
    assertThat(service.search(actor, "valid", fresh()).id).isNotBlank();
  }

  @Test
  void exactEtaBoundaryIsOldButObservationBoundaryRemainsValid() {
    assertThat(service.reconcileSearch(actor, "now", request(START, START)).reason())
        .isEqualTo("INVALID_TIME");
    fails(
        "REQUEST_NOT_CLOSABLE",
        () ->
            service.reconcileSearch(
                actor, "five", request(START.plusSeconds(3600), START.minusSeconds(300))));
  }

  @Test
  void concurrentReconciliationAndReplayHaveOneDurableClosure() throws Exception {
    var body = request(START.minusSeconds(1), START);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<WorkflowService.SearchResolution>>();
      for (int i = 0; i < 8; i++)
        tasks.add(() -> service.reconcileSearch(actor, "concurrent", body));
      var results = pool.invokeAll(tasks);
      for (var result : results) assertThat(result.get()).isEqualTo(results.getFirst().get());
    }
    fails("SEARCH_COMMAND_CLOSED", () -> service.search(actor, "concurrent", body));
  }

  @Test
  void raceWithNormalSearchNeverCreatesAndClosesSameCommand() throws Exception {
    var body = fresh();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var search = pool.submit(() -> service.search(actor, "race", body));
      var resolution =
          pool.submit(
              () -> {
                try {
                  return service.reconcileSearch(actor, "race", body);
                } catch (ApiError error) {
                  assertThat(error.code).isEqualTo("REQUEST_NOT_CLOSABLE");
                  return null;
                }
              });
      var found = search.get(10, TimeUnit.SECONDS);
      var resolved = resolution.get(10, TimeUnit.SECONDS);
      if (resolved != null) assertThat(resolved.searchId()).isEqualTo(found.id);
      assertThat(service.reconcileSearch(actor, "race", body).searchId()).isEqualTo(found.id);
    }
  }

  @Test
  void actualTokenRequiredAndInvalidKeyOrBodyRejected() throws Exception {
    var body = request(START.minusSeconds(1), START);
    http.perform(
            post("/api/v1/searches/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "http")
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isUnauthorized());
    var issued =
        http.perform(
                post("/api/v1/anonymous-sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn();
    String token =
        json.readTree(issued.getResponse().getContentAsString()).get("accessToken").asText();
    http.perform(
            post("/api/v1/searches/reconcile")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("REJECTED_CLOSED"));
    http.perform(
            post("/api/v1/searches/reconcile")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "invalid key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_KEY"));
    http.perform(
            post("/api/v1/searches/reconcile")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "empty")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
