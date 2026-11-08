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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WorkflowTest {
  private static final RequestActor ACTOR = new RequestActor("test-owner", true);
  static final Instant START = Instant.parse("2026-11-04T06:00:00Z");
  @Autowired WorkflowService service;
  @Autowired Store store;
  @Autowired MutableClock clock;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    Instant now = START;

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
  void resetTime() {
    clock.now = START;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  SearchRequest request(String trip) {
    return new SearchRequest(
        new Navigation(
            trip,
            new Destination("가상 목적지", 37.544, 127.055),
            START.plusSeconds(3600),
            clock.instant(),
            NavigationSource.MOCK),
        new Party(2, 1),
        15,
        Set.of("parking"),
        Set.of("한식"),
        false);
  }

  Search fresh() {
    return service.search(ACTOR, key(), request(key()));
  }

  OutcomeRequest outcome(
      Outcome result, boolean accepted, Instant time, int cost, InquiryFacts facts) {
    return new OutcomeRequest(key(), result, accepted, clock.instant(), time, cost, facts);
  }

  Search ready() {
    return ready(fresh());
  }

  Search ready(Search s) {
    CallTask inquiry = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    service.outcome(
        ACTOR,
        inquiry.id,
        outcome(
            Outcome.ANSWERED,
            false,
            null,
            0,
            new InquiryFacts(true, true, true, true, 25, 10, s.request.navigation().eta())));
    return service.getSearch(ACTOR, s.id);
  }

  BookingRequest booking(Search s) {
    return new BookingRequest(
        "mock-1",
        s.request.navigation().eta(),
        true,
        new ChangePolicy(true, START.plusSeconds(7200), 10000));
  }

  Reservation confirmed() {
    Search s = ready();
    Reservation r = service.book(ACTOR, s.id, key(), booking(s));
    service.outcome(
        ACTOR, r.bookingCallId, outcome(Outcome.ACCEPTED, true, r.originalRequestedTime, 0, null));
    return service.getReservation(ACTOR, r.id);
  }

  void expectCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code).isEqualTo(code));
  }

  @Test
  void fullFlowRequiresExplicitMatchingAcceptance() {
    Search s = ready();
    Candidate c =
        s.candidates.stream().filter(it -> it.id.equals("mock-1")).findFirst().orElseThrow();
    assertThat(c.bookable).isTrue();
    assertThat(c.facts.get("nowWaitMinutes").scope()).isEqualTo(Scope.NOW);
    assertThat(c.facts.get("arrivalWaitMinutes").certainty()).isEqualTo(Certainty.ESTIMATED);
    assertThat(c.facts.get("parking").mode()).isEqualTo(Mode.MOCK);
    Reservation r = service.book(ACTOR, s.id, key(), booking(s));
    assertThat(r.confirmedTime).isNull();
    assertThat(r.status).isEqualTo(ReservationStatus.PENDING);
    service.outcome(
        ACTOR, r.bookingCallId, outcome(Outcome.ACCEPTED, true, r.originalRequestedTime, 0, null));
    assertThat(service.getReservation(ACTOR, r.id).status).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(service.getReservation(ACTOR, r.id).confirmedTime)
        .isEqualTo(r.originalRequestedTime);
  }

  @Test
  void unknownRequiredFactsBlockBookingAndNowDoesNotBecomeArrival() {
    Search s = fresh();
    CallTask call = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    service.outcome(
        ACTOR,
        call.id,
        outcome(
            Outcome.ANSWERED,
            false,
            null,
            0,
            new InquiryFacts(true, true, true, true, 5, null, s.request.navigation().eta())));
    Candidate c =
        service.getSearch(ACTOR, s.id).candidates.stream()
            .filter(it -> it.id.equals("mock-1"))
            .findFirst()
            .orElseThrow();
    assertThat(c.missing).contains("arrivalWaitMinutes");
    expectCode("CANDIDATE_NOT_READY", () -> service.book(ACTOR, s.id, key(), booking(s)));
  }

  @Test
  void familyRequirementOverridesCuisinePreference() {
    Search s = fresh();
    CallTask c = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    service.outcome(
        ACTOR,
        c.id,
        outcome(
            Outcome.ANSWERED,
            false,
            null,
            0,
            new InquiryFacts(false, true, true, true, 0, 0, s.request.navigation().eta())));
    Search ranked = service.getSearch(ACTOR, s.id);
    assertThat(ranked.candidates.getLast().id).isEqualTo("mock-1");
    assertThat(ranked.candidates.getLast().exclusionReasons).contains("childFriendly");
    expectCode("CANDIDATE_NOT_READY", () -> service.book(ACTOR, s.id, key(), booking(s)));
  }

  @Test
  void unavailableReservationIsExcludedDespiteEmptySeats() {
    Search s = fresh();
    CallTask c = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    service.outcome(
        ACTOR,
        c.id,
        outcome(
            Outcome.ANSWERED,
            false,
            null,
            0,
            new InquiryFacts(true, true, true, false, 0, 0, s.request.navigation().eta())));
    expectCode("CANDIDATE_NOT_READY", () -> service.book(ACTOR, s.id, key(), booking(s)));
  }

  @Test
  void expiredFactsAreReevaluatedOnReadAndBooking() {
    Search s = ready();
    clock.now = START.plusSeconds(301);
    assertThat(service.getSearch(ACTOR, s.id).candidates).allMatch(c -> !c.bookable);
    expectCode("CANDIDATE_NOT_READY", () -> service.book(ACTOR, s.id, key(), booking(s)));
  }

  @Test
  void idempotencyReplaysAndRejectsDifferentPayload() {
    String k = key();
    SearchRequest req = request(key());
    Search first = service.search(ACTOR, k, req);
    assertThat(service.search(ACTOR, k, req).id).isEqualTo(first.id);
    expectCode("KEY_REUSED", () -> service.search(ACTOR, k, request(key())));
    expectCode("INVALID_KEY", () -> service.search(ACTOR, " ", req));
  }

  @Test
  void eventsReplayAndCannotBeRebound() {
    Search s = ready();
    Reservation r = service.book(ACTOR, s.id, key(), booking(s));
    OutcomeRequest e = outcome(Outcome.ACCEPTED, true, r.originalRequestedTime, 0, null);
    service.outcome(ACTOR, r.bookingCallId, e);
    service.outcome(ACTOR, r.bookingCallId, e);
    expectCode(
        "EVENT_REUSED",
        () ->
            service.outcome(
                ACTOR,
                r.bookingCallId,
                new OutcomeRequest(
                    e.eventId(), Outcome.REJECTED, false, clock.instant(), null, 0, null)));
    expectCode(
        "CALL_FINALIZED",
        () ->
            service.outcome(
                ACTOR, r.bookingCallId, outcome(Outcome.UNKNOWN, false, null, 0, null)));
    assertThat(service.getReservation(ACTOR, r.id).status).isEqualTo(ReservationStatus.CONFIRMED);
  }

  @ParameterizedTest
  @EnumSource(
      value = Outcome.class,
      names = {"ANSWERED", "UNKNOWN"})
  void completionAloneIsUnknownAndBlocksNewBookingEvenAcrossSearches(Outcome result) {
    Search s = ready();
    Reservation r = service.book(ACTOR, s.id, key(), booking(s));
    service.outcome(ACTOR, r.bookingCallId, outcome(result, false, null, 0, null));
    assertThat(service.getReservation(ACTOR, r.id).status).isEqualTo(ReservationStatus.UNKNOWN);
    expectCode("ACTIVE_RESERVATION", () -> service.book(ACTOR, s.id, key(), booking(s)));
    Search other = ready(service.search(ACTOR, key(), request(s.request.navigation().tripId())));
    expectCode("ACTIVE_RESERVATION", () -> service.book(ACTOR, other.id, key(), booking(other)));
  }

  @Test
  void mismatchingTimeCostOrImplicitAcceptanceNeverConfirms() {
    for (int scenario = 0; scenario < 3; scenario++) {
      Search s = ready();
      Reservation r = service.book(ACTOR, s.id, key(), booking(s));
      service.outcome(
          ACTOR,
          r.bookingCallId,
          outcome(
              Outcome.ACCEPTED,
              scenario != 2,
              r.originalRequestedTime.plusSeconds(scenario == 0 ? 60 : 0),
              scenario == 1 ? 100 : 0,
              null));
      assertThat(service.getReservation(ACTOR, r.id).status).isEqualTo(ReservationStatus.UNKNOWN);
      assertThat(service.getReservation(ACTOR, r.id).confirmedTime).isNull();
    }
  }

  @Test
  void rejectedBookingAllowsAnExplicitNewAttempt() {
    Search s = ready();
    Reservation r = service.book(ACTOR, s.id, key(), booking(s));
    service.outcome(ACTOR, r.bookingCallId, outcome(Outcome.REJECTED, false, null, 0, null));
    assertThat(service.book(ACTOR, s.id, key(), booking(s)).id).isNotEqualTo(r.id);
  }

  @Test
  void changePreservesOriginalUntilMatchingAcceptance() {
    Reservation r = confirmed();
    Instant changed = r.confirmedTime.plusSeconds(900);
    Reservation pending =
        service.change(ACTOR, r.id, key(), new ChangeRequest(changed, 1000, true));
    assertThat(pending.confirmedTime).isEqualTo(r.confirmedTime);
    service.outcome(
        ACTOR, pending.pendingChangeCallId, outcome(Outcome.ACCEPTED, true, changed, 1000, null));
    Reservation accepted = service.getReservation(ACTOR, r.id);
    assertThat(accepted.confirmedTime).isEqualTo(changed);
    assertThat(accepted.originalRequestedTime).isEqualTo(r.originalRequestedTime);
    assertThat(accepted.totalAdditionalCostKrw).isEqualTo(1000);
    assertThat(accepted.pendingChangeCallId).isNull();
  }

  @Test
  void unknownChangeKeepsBothTimesAndBlocksRetry() {
    Reservation r = confirmed();
    Reservation pending =
        service.change(
            ACTOR, r.id, key(), new ChangeRequest(r.confirmedTime.plusSeconds(900), 0, false));
    service.outcome(
        ACTOR,
        pending.pendingChangeCallId,
        outcome(Outcome.ACCEPTED, true, r.confirmedTime.plusSeconds(1200), 0, null));
    Reservation unknown = service.getReservation(ACTOR, r.id);
    assertThat(unknown.status).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(unknown.confirmedTime).isEqualTo(r.confirmedTime);
    assertThat(unknown.pendingChangeCallId).isEqualTo(pending.pendingChangeCallId);
    clock.now = START.plusSeconds(700);
    expectCode(
        "CHANGE_BLOCKED",
        () ->
            service.change(
                ACTOR,
                r.id,
                key(),
                new ChangeRequest(r.confirmedTime.plusSeconds(1500), 0, false)));
  }

  @Test
  void rejectedChangePreservesConfirmationAndEnforcesCooldown() {
    Reservation r = confirmed();
    ChangeRequest change = new ChangeRequest(r.confirmedTime.plusSeconds(900), 0, false);
    Reservation pending = service.change(ACTOR, r.id, key(), change);
    service.outcome(
        ACTOR, pending.pendingChangeCallId, outcome(Outcome.REJECTED, false, null, 0, null));
    assertThat(service.getReservation(ACTOR, r.id).confirmedTime).isEqualTo(r.confirmedTime);
    expectCode("CHANGE_COOLDOWN", () -> service.change(ACTOR, r.id, key(), change));
    clock.now = START.plusSeconds(600);
    assertThat(service.change(ACTOR, r.id, key(), change).pendingChangeCallId).isNotNull();
  }

  @Test
  void changeRequiresPolicyAndCostApproval() {
    Reservation r = confirmed();
    expectCode(
        "COST_APPROVAL_REQUIRED",
        () ->
            service.change(
                ACTOR,
                r.id,
                key(),
                new ChangeRequest(r.confirmedTime.plusSeconds(300), 100, false)));
    expectCode(
        "OUTSIDE_CHANGE_POLICY",
        () ->
            service.change(
                ACTOR, r.id, key(), new ChangeRequest(START.plusSeconds(8000), 0, false)));
    expectCode(
        "OUTSIDE_CHANGE_POLICY",
        () ->
            service.change(
                ACTOR,
                r.id,
                key(),
                new ChangeRequest(r.confirmedTime.plusSeconds(300), 11000, true)));
  }

  @Test
  void invalidOutcomeRollsBackEventAndCanBeCorrected() {
    Search s = fresh();
    CallTask c = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    String eventId = key();
    expectCode(
        "ARRIVAL_TIME_MISMATCH",
        () ->
            service.outcome(
                ACTOR,
                c.id,
                new OutcomeRequest(
                    eventId,
                    Outcome.ANSWERED,
                    false,
                    clock.instant(),
                    null,
                    0,
                    new InquiryFacts(true, true, true, true, 0, 0, START))));
    assertThat(service.getCall(ACTOR, c.id).status).isEqualTo(CallStatus.PENDING);
    service.outcome(
        ACTOR,
        c.id,
        new OutcomeRequest(
            eventId,
            Outcome.ANSWERED,
            false,
            clock.instant(),
            null,
            0,
            new InquiryFacts(true, true, true, true, 0, 0, s.request.navigation().eta())));
  }

  @Test
  void concurrentSameKeyCreatesOneCallAndReservation() throws Exception {
    Search s = ready();
    String k = key();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.book(ACTOR, s.id, k, booking(s)).id;
                }));
      start.countDown();
      Set<String> ids = new HashSet<>();
      for (Future<String> f : futures) ids.add(f.get(10, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(service.getCalls(ACTOR, s.id).stream().filter(c -> c.purpose == Purpose.BOOKING))
          .hasSize(1);
    }
  }

  @Test
  void concurrentDifferentKeysStillCreateOneBooking() throws Exception {
    Search s = ready();
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    service.book(ACTOR, s.id, key(), booking(s));
                    return true;
                  } catch (ApiError e) {
                    assertThat(e.code).isEqualTo("ACTIVE_RESERVATION");
                    return false;
                  }
                }));
      start.countDown();
      int created = 0;
      for (Future<Boolean> f : futures) if (f.get(10, TimeUnit.SECONDS)) created++;
      assertThat(created).isEqualTo(1);
    }
  }

  @Test
  void liveModeIsBlocked() {
    assertThatThrownBy(() -> new MockCallAdapter("LIVE")).hasMessageContaining("LIVE_DISABLED");
    assertThatThrownBy(() -> new MockCallAdapter("live")).hasMessageContaining("LIVE_DISABLED");
  }

  @Test
  void httpValidationAndModeAreExplicit() throws Exception {
    http.perform(get("/api/v1/status").principal(() -> "test-owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("MOCK"))
        .andExpect(jsonPath("$.externalCallsEnabled").value(false));
    http.perform(
            post("/api/v1/searches")
                .principal(() -> "test-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(key()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    String valid = json.writeValueAsString(request(key()));
    for (String invalid :
        List.of(
            valid.replace("\"parking\"", "\"unknownFeature\""),
            valid.replace("\"adults\":2", "\"adults\":0"),
            valid.replace("\"latitude\":37.544", "\"latitude\":null"),
            valid.replace("\"adults\":2", "\"adults\":2.5"),
            valid.replace("\"adults\":2", "\"adults\":\"2\""))) {
      http.perform(
              post("/api/v1/searches")
                  .principal(() -> "test-owner")
                  .header("Idempotency-Key", key())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    http.perform(
            post("/api/v1/searches")
                .principal(() -> "test-owner")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(valid.replace("\"MOCK\"", "\"PLEOS\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PLEOS_ETA_UNVERIFIED"));
    http.perform(get("/api/v1/reservations/missing").principal(() -> "test-owner"))
        .andExpect(status().isNotFound());
  }

  @Test
  void lateObservationDoesNotGetAFreshExpiration() {
    Search s = fresh();
    CallTask c = service.inquire(ACTOR, s.id, key(), new InquiryRequest("mock-1"));
    clock.now = START.plusSeconds(301);
    service.outcome(
        ACTOR,
        c.id,
        new OutcomeRequest(
            key(),
            Outcome.ANSWERED,
            false,
            START,
            null,
            0,
            new InquiryFacts(true, true, true, true, 0, 0, s.request.navigation().eta())));
    expectCode("CANDIDATE_NOT_READY", () -> service.book(ACTOR, s.id, key(), booking(s)));
    Candidate candidate =
        service.getSearch(ACTOR, s.id).candidates.stream()
            .filter(item -> item.id.equals("mock-1"))
            .findFirst()
            .orElseThrow();
    assertThat(candidate.facts.get("parking").observedAt()).isEqualTo(START);
    assertThat(candidate.facts.get("parking").validUntil()).isEqualTo(START.plusSeconds(300));
  }

  @Test
  void cumulativeChangeCostCannotExceedPolicy() {
    Reservation r = confirmed();
    Reservation pending =
        service.change(
            ACTOR, r.id, key(), new ChangeRequest(r.confirmedTime.plusSeconds(300), 7000, true));
    service.outcome(
        ACTOR,
        pending.pendingChangeCallId,
        outcome(Outcome.ACCEPTED, true, r.confirmedTime.plusSeconds(300), 7000, null));
    clock.now = START.plusSeconds(600);
    expectCode(
        "OUTSIDE_CHANGE_POLICY",
        () ->
            service.change(
                ACTOR,
                r.id,
                key(),
                new ChangeRequest(r.confirmedTime.plusSeconds(600), 4000, true)));
  }

  @Test
  void concurrentDuplicateEventsDoNotApplyChangeCostTwice() throws Exception {
    Reservation r = confirmed();
    Reservation pending =
        service.change(
            ACTOR, r.id, key(), new ChangeRequest(r.confirmedTime.plusSeconds(300), 1000, true));
    OutcomeRequest event =
        outcome(Outcome.ACCEPTED, true, r.confirmedTime.plusSeconds(300), 1000, null);
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<CallTask>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.outcome(ACTOR, pending.pendingChangeCallId, event);
                }));
      start.countDown();
      for (Future<CallTask> future : futures)
        assertThat(future.get(10, TimeUnit.SECONDS).status).isEqualTo(CallStatus.ACCEPTED);
      assertThat(service.getReservation(ACTOR, r.id).totalAdditionalCostKrw).isEqualTo(1000);
    }
  }

  @Test
  void featureSetOrderDoesNotChangeCommandIdentity() {
    SearchRequest original = request(key());
    String k = key();
    SearchRequest first =
        new SearchRequest(
            original.navigation(),
            original.party(),
            15,
            new LinkedHashSet<>(List.of("parking", "childFriendly")),
            Set.of("한식"),
            false);
    SearchRequest second =
        new SearchRequest(
            original.navigation(),
            original.party(),
            15,
            new LinkedHashSet<>(List.of("childFriendly", "parking")),
            Set.of("한식"),
            false);
    assertThat(service.search(ACTOR, k, first).id).isEqualTo(service.search(ACTOR, k, second).id);
  }

  @Test
  void learningConsentDoesNotAuthorizeBooking() throws Exception {
    SearchRequest input = request(key());
    Search s =
        ready(
            service.search(
                ACTOR,
                key(),
                new SearchRequest(
                    input.navigation(),
                    input.party(),
                    input.maxWaitMinutes(),
                    input.requiredFeatures(),
                    input.preferredCuisines(),
                    true)));
    String body =
        json.writeValueAsString(
            new BookingRequest(
                "mock-1", s.request.navigation().eta(), false, new ChangePolicy(false, null, 0)));
    http.perform(
            post("/api/v1/searches/" + s.id + "/reservations")
                .principal(() -> "test-owner")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    assertThat(service.getCalls(ACTOR, s.id)).allMatch(c -> c.purpose == Purpose.INQUIRY);
  }
}
