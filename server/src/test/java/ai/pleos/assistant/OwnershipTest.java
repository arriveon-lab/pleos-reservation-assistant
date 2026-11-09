package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ownership;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OwnershipTest {
  @Autowired WorkflowService service;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  String key() {
    return UUID.randomUUID().toString();
  }

  RequestActor actor() {
    return new RequestActor(key(), true);
  }

  SearchRequest request(String trip) {
    return new SearchRequest(
        new Navigation(
            trip,
            new Destination("가상 목적지", 37.544, 127.055),
            Instant.now().plusSeconds(3600),
            Instant.now(),
            NavigationSource.MOCK),
        new Party(2, 1),
        15,
        Set.of("parking"),
        Set.of("한식"),
        false);
  }

  OutcomeRequest answered(Search s) {
    return new OutcomeRequest(
        key(),
        Outcome.ANSWERED,
        false,
        Instant.now(),
        null,
        0,
        new InquiryFacts(true, true, true, true, 25, 10, s.request.navigation().eta()));
  }

  Search ready(RequestActor actor, SearchRequest request) {
    Search s = service.search(actor, key(), request);
    CallTask c = service.inquire(actor, s.id, key(), new InquiryRequest("mock-1"));
    service.outcome(actor, c.id, answered(s));
    return service.getSearch(actor, s.id);
  }

  BookingRequest booking(Search s) {
    return new BookingRequest(
        "mock-1",
        s.request.navigation().eta(),
        true,
        new ChangePolicy(true, s.request.navigation().eta().plusSeconds(3600), 1000));
  }

  void denied(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiError.class, error -> assertThat(error.status).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void sameKeyAndTripAreIndependentAcrossUsersButReplayIsStable() {
    RequestActor a = actor(), b = actor();
    String key = key();
    SearchRequest request = request(key());
    Search first = service.search(a, key, request), other = service.search(b, key, request);
    assertThat(first.id).isNotEqualTo(other.id);
    assertThat(service.search(a, key, request).id).isEqualTo(first.id);
    assertThatThrownBy(() -> service.search(a, key, request(key())))
        .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code).isEqualTo("KEY_REUSED"));
    denied(() -> service.getSearch(b, first.id));
    Search repeat = service.search(a, key(), request);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT journey_id) FROM searches WHERE id IN (?,?)",
                Integer.class,
                first.id,
                repeat.id))
        .isEqualTo(1);
  }

  @Test
  void otherOwnerCannotReadMutateOrReplayAnyResource() throws Exception {
    RequestActor a = actor(), b = actor();
    Search s = ready(a, request(key()));
    String inquiryKey = key();
    CallTask c = service.inquire(a, s.id, inquiryKey, new InquiryRequest("mock-3"));
    String bookKey = key();
    Reservation reservation = service.book(a, s.id, bookKey, booking(s));
    denied(() -> service.getSearch(b, s.id));
    denied(() -> service.getCalls(b, s.id));
    denied(() -> service.getCall(b, c.id));
    denied(() -> service.getReservation(b, reservation.id));
    denied(() -> service.inquire(b, s.id, inquiryKey, new InquiryRequest("mock-3")));
    denied(() -> service.book(b, s.id, bookKey, booking(s)));
    denied(() -> service.outcome(b, c.id, answered(s)));
    service.outcome(
        a,
        reservation.bookingCallId,
        new OutcomeRequest(
            key(),
            Outcome.ACCEPTED,
            true,
            Instant.now(),
            reservation.originalRequestedTime,
            0,
            null));
    String changeKey = key();
    ChangeRequest change =
        new ChangeRequest(reservation.originalRequestedTime.plusSeconds(60), 0, false);
    service.change(a, reservation.id, changeKey, change);
    denied(() -> service.change(b, reservation.id, changeKey, change));
    for (String path :
        List.of(
            "/searches/" + s.id,
            "/searches/" + s.id + "/calls",
            "/calls/" + c.id,
            "/reservations/" + reservation.id)) {
      http.perform(get("/api/v1" + path).principal(b::subject))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    http.perform(
            post("/api/v1/searches/" + s.id + "/reservations")
                .principal(b::subject)
                .header("Idempotency-Key", bookKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(booking(s))))
        .andExpect(status().isNotFound());
  }

  @Test
  void mockOutcomeNeedsTrustedRoleAndOwnershipEvenOnReplay() throws Exception {
    RequestActor a = actor();
    Search s = service.search(a, key(), request(key()));
    CallTask c = service.inquire(a, s.id, key(), new InquiryRequest("mock-1"));
    OutcomeRequest event = answered(s);
    String path = "/api/v1/mock/calls/" + c.id + "/outcomes";
    http.perform(
            post(path)
                .principal(a::subject)
                .header("X-Role", "MOCK_OPERATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event)))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> service.outcome(new RequestActor(a.subject(), false), c.id, event))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.status).isEqualTo(HttpStatus.FORBIDDEN));
    http.perform(
            post(path)
                .principal(a::subject)
                .with(
                    req -> {
                      req.addUserRole("MOCK_OPERATOR");
                      return req;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event)))
        .andExpect(status().isOk());
    RequestActor b = actor();
    http.perform(
            post(path)
                .principal(b::subject)
                .with(
                    req -> {
                      req.addUserRole("MOCK_OPERATOR");
                      return req;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event)))
        .andExpect(status().isNotFound());
    assertThat(service.getCall(a, c.id).status).isEqualTo(CallStatus.ANSWERED);
  }

  @Test
  void sameTripAllowsDifferentUsersButBlocksDuplicateReservationsForOneUser() {
    RequestActor a = actor(), b = actor();
    SearchRequest request = request(key());
    Search sa = ready(a, request), sb = ready(b, request), repeat = ready(a, request);
    service.book(a, sa.id, key(), booking(sa));
    assertThat(service.book(b, sb.id, key(), booking(sb)).status)
        .isEqualTo(ReservationStatus.PENDING);
    assertThatThrownBy(() -> service.book(a, repeat.id, key(), booking(repeat)))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.code).isEqualTo("ACTIVE_RESERVATION"));
  }

  @Test
  void concurrentFirstRequestsCreateOneUserJourneyAndReplay() throws Exception {
    RequestActor a = actor();
    String key = key();
    SearchRequest request = request(key());
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 4; i++)
        futures.add(pool.submit(() -> service.search(a, key, request).id));
      Set<String> ids = new HashSet<>();
      for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE subject=?", Integer.class, a.subject()))
        .isEqualTo(1);
  }
}
