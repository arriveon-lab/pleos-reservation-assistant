package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
  private final Store store;
  private final RecommendationEngine recommendations;
  private final MockPlaceSearchAdapter places;
  private final MockCandidateFactory candidates;
  private final MockCallAdapter calls;
  private final Clock clock;

  public WorkflowService(
      Store store,
      RecommendationEngine recommendations,
      MockPlaceSearchAdapter places,
      MockCandidateFactory candidates,
      MockCallAdapter calls,
      Clock clock) {
    this.store = store;
    this.recommendations = recommendations;
    this.places = places;
    this.candidates = candidates;
    this.calls = calls;
    this.clock = clock;
  }

  @Transactional
  public Search search(RequestActor actor, String key, SearchRequest request) {
    var closure =
        store.completedCommand(actor, "search-closure", key, request, SearchResolution.class);
    if (closure.isPresent() && closure.get().state().equals("REJECTED_CLOSED"))
      throw ApiError.conflict("SEARCH_COMMAND_CLOSED", "종료한 검색 요청입니다. 새 검색은 새 키로 시작하세요.");
    return store.command(
        actor,
        "search",
        key,
        request,
        Search.class,
        () -> {
          Navigation n = request.navigation();
          if (n.source() == NavigationSource.PLEOS)
            throw ApiError.bad(
                "PLEOS_ETA_UNVERIFIED", "Pleos ETA 시간 기준·기기 검증 전에는 MOCK 또는 MANUAL 입력만 가능합니다.");
          requireFuture(n.eta());
          if (n.observedAt().isAfter(clock.instant().plusSeconds(5))
              || n.observedAt().isBefore(clock.instant().minusSeconds(300)))
            throw ApiError.bad("STALE_NAVIGATION", "목적지·ETA 관측은 최근 5분 이내여야 합니다.");
          Search s = new Search();
          s.id = id();
          s.createdAt = clock.instant();
          s.request = request;
          // MOCK is local-only. A network adapter requires a separate transaction/idempotency
          // design.
          s.candidates =
              candidates.create(places.search(PlaceQuery.forDestination(n.destination())));
          recommendations.select(s);
          store.insertSearch(actor, s);
          return s;
        });
  }

  public record SearchResolution(
      String state, String mode, String searchId, String reason, Instant resolvedAt) {}

  /** Reconcile the original command without executing a search or changing its input. */
  @Transactional
  public SearchResolution reconcileSearch(RequestActor actor, String key, SearchRequest request) {
    return store.command(
        actor,
        "search-closure",
        key,
        request,
        SearchResolution.class,
        () -> {
          var completed = store.completedCommand(actor, "search", key, request, Search.class);
          if (completed.isPresent())
            return new SearchResolution(
                "COMPLETED", "MOCK", completed.get().id, null, clock.instant());
          Navigation n = request.navigation();
          if (n.source() == NavigationSource.PLEOS)
            throw ApiError.conflict("REQUEST_NOT_CLOSABLE", "검증되지 않은 차량 입력은 이 절차로 종료할 수 없습니다.");
          Instant now = clock.instant();
          // Only definitively old input can be closed. Future timestamps may become valid later.
          String reason =
              !n.eta().isAfter(now)
                  ? "INVALID_TIME"
                  : n.observedAt().isBefore(now.minusSeconds(300)) ? "STALE_NAVIGATION" : null;
          if (reason == null)
            throw ApiError.conflict("REQUEST_NOT_CLOSABLE", "처리 결과가 없지만 오래된 요청으로 확정할 수 없습니다.");
          return new SearchResolution("REJECTED_CLOSED", "MOCK", null, reason, now);
        });
  }

  @Transactional(readOnly = true)
  public Search getSearch(RequestActor actor, String id) {
    store.requireOwner(actor, "searches", id);
    Search s = store.get("searches", id, Search.class);
    recommendations.rank(s); // Expiration is evaluated on every read, not only on writes.
    return s;
  }

  @Transactional(readOnly = true)
  public Reservation getReservation(RequestActor actor, String id) {
    store.requireOwner(actor, "reservations", id);
    return store.get("reservations", id, Reservation.class);
  }

  @Transactional(readOnly = true)
  public CallTask getCall(RequestActor actor, String id) {
    store.requireOwner(actor, "calls", id);
    return store.get("calls", id, CallTask.class);
  }

  @Transactional(readOnly = true)
  public List<CallTask> getCalls(RequestActor actor, String searchId) {
    store.requireOwner(actor, "searches", searchId);
    store.get("searches", searchId, Search.class);
    return store.bySearch("calls", searchId, CallTask.class);
  }

  @Transactional
  public CallTask inquire(RequestActor actor, String searchId, String key, InquiryRequest request) {
    store.lock();
    store.requireOwner(actor, "searches", searchId);
    return store.command(
        actor,
        "inquiry:" + searchId,
        key,
        request,
        CallTask.class,
        () -> {
          Search s = getSearch(actor, searchId);
          Candidate c = candidate(s, request.candidateId());
          requireFuture(s.request.navigation().eta());
          if (!c.eligible) throw ApiError.conflict("INELIGIBLE_CANDIDATE", "필수 조건에 맞지 않는 후보입니다.");
          for (CallTask existing : store.bySearch("calls", searchId, CallTask.class)) {
            if (existing.purpose == Purpose.INQUIRY
                && existing.candidateId.equals(c.id)
                && (existing.status == CallStatus.PENDING
                    || existing.createdAt.plusSeconds(600).isAfter(clock.instant())))
              throw ApiError.conflict("INQUIRY_COOLDOWN", "동일 후보 문의가 진행 중이거나 최근 10분 이내에 요청됐습니다.");
          }
          return createCall(s, c.id, null, Purpose.INQUIRY, s.request.navigation().eta(), 0);
        });
  }

  @Transactional
  public Reservation book(RequestActor actor, String searchId, String key, BookingRequest request) {
    store.lock();
    store.requireOwner(actor, "searches", searchId);
    return store.command(
        actor,
        "booking:" + searchId,
        key,
        request,
        Reservation.class,
        () -> {
          Search s = getSearch(actor, searchId);
          Candidate c = candidate(s, request.candidateId());
          requireFuture(request.requestedTime());
          if (!request.allowBooking())
            throw ApiError.bad("BOOKING_PERMISSION_REQUIRED", "예약 권한이 필요합니다.");
          if (!request.requestedTime().equals(s.request.navigation().eta()))
            throw ApiError.conflict(
                "BOOKING_TIME_MISMATCH", "확인한 ETA와 예약 요청 시각이 다릅니다. 새 검색·문의가 필요합니다.");
          if (!c.bookable)
            throw ApiError.conflict("CANDIDATE_NOT_READY", "필수 조건·도착 대기·예약 가능 여부를 먼저 확인하세요.");
          if (request.changePolicy().enabled()
              && (request.changePolicy().latestTime() == null
                  || request.changePolicy().latestTime().isBefore(request.requestedTime())))
            throw ApiError.bad("INVALID_CHANGE_POLICY", "변경 허용 종료 시각은 예약 시각 이후여야 합니다.");
          for (Reservation existing : store.byTrip(actor, s.request.navigation().tripId())) {
            if (existing.status != ReservationStatus.REJECTED)
              throw ApiError.conflict(
                  "ACTIVE_RESERVATION", "이 여정에 진행·확정·미확인 예약이 있습니다. 재발신하지 않습니다.");
          }
          Reservation r = new Reservation();
          r.id = id();
          r.searchId = s.id;
          r.candidateId = c.id;
          r.tripId = s.request.navigation().tripId();
          r.originalRequestedTime = request.requestedTime();
          r.changePolicy = request.changePolicy();
          CallTask call = createCall(s, c.id, r.id, Purpose.BOOKING, request.requestedTime(), 0);
          r.bookingCallId = call.id;
          store.insertReservation(r);
          return r;
        });
  }

  @Transactional
  public Reservation change(
      RequestActor actor, String reservationId, String key, ChangeRequest request) {
    store.lock();
    store.requireOwner(actor, "reservations", reservationId);
    return store.command(
        actor,
        "change:" + reservationId,
        key,
        request,
        Reservation.class,
        () -> {
          Reservation r = getReservation(actor, reservationId);
          if (r.status != ReservationStatus.CONFIRMED || r.pendingChangeCallId != null)
            throw ApiError.conflict("CHANGE_BLOCKED", "확정된 예약이고 진행·미확인 변경이 없어야 합니다.");
          requireFuture(request.requestedTime());
          if (!r.changePolicy.enabled()
              || r.changePolicy.latestTime() == null
              || request.requestedTime().isAfter(r.changePolicy.latestTime())
              || !request.requestedTime().isAfter(r.confirmedTime)
              || request.additionalCostKrw()
                  > r.changePolicy.maxAdditionalCostKrw() - r.totalAdditionalCostKrw)
            throw ApiError.conflict(
                "OUTSIDE_CHANGE_POLICY", "허용 시간·누적 비용 범위 밖입니다. 새 사용자 확인이 필요합니다.");
          if (request.additionalCostKrw() > 0 && !request.approveAdditionalCost())
            throw ApiError.conflict("COST_APPROVAL_REQUIRED", "추가 비용은 별도의 사용자 확인이 필요합니다.");
          if (r.lastChangeRequestedAt != null
              && r.lastChangeRequestedAt.plusSeconds(600).isAfter(clock.instant()))
            throw ApiError.conflict("CHANGE_COOLDOWN", "변경 요청 간격은 최소 10분입니다.");
          CallTask call =
              createCall(
                  getSearch(actor, r.searchId),
                  r.candidateId,
                  r.id,
                  Purpose.CHANGE,
                  request.requestedTime(),
                  request.additionalCostKrw());
          r.pendingChangeCallId = call.id;
          r.changeCallIds.add(call.id);
          r.lastChangeRequestedAt = clock.instant();
          store.save("reservations", r.id, r);
          return r;
        });
  }

  @Transactional
  public CallTask outcome(RequestActor actor, String callId, OutcomeRequest event) {
    actor.requireMockOperator();
    store.lock();
    CallTask call = getCall(actor, callId);
    if (!store.recordEvent(callId, event)) return call;
    if (call.status != CallStatus.PENDING)
      throw ApiError.conflict("CALL_FINALIZED", "이미 종료된 통화입니다. 미확인은 별도 대조 절차가 필요합니다.");
    if (event.observedAt().isBefore(call.createdAt)
        || event.observedAt().isAfter(clock.instant().plusSeconds(5)))
      throw ApiError.bad("INVALID_OBSERVATION", "관측 시각은 통화 요청 이후이고 미래가 아니어야 합니다.");
    if (call.purpose == Purpose.INQUIRY) {
      if (event.outcome() == Outcome.ACCEPTED
          || event.explicitAcceptance()
          || event.acceptedTime() != null
          || event.additionalCostKrw() != 0)
        throw ApiError.bad("INVALID_INQUIRY_OUTCOME", "문의는 예약 수락으로 처리하지 않습니다.");
      if (event.facts() != null && event.outcome() != Outcome.ANSWERED)
        throw ApiError.bad("FACTS_WITHOUT_ANSWER", "확인되지 않은 통화에서는 사실을 반영하지 않습니다.");
      call.status = CallStatus.valueOf(event.outcome().name());
      if (event.facts() != null) {
        Search s = getSearch(actor, call.searchId);
        recommendations.apply(
            candidate(s, call.candidateId),
            event.facts(),
            call.id,
            s.request.navigation().eta(),
            event.observedAt());
        recommendations.rank(s);
        store.save("searches", s.id, s);
      }
    } else {
      if (event.facts() != null)
        throw ApiError.bad("UNEXPECTED_FACTS", "예약 결과에는 문의 사실을 함께 보내지 않습니다.");
      boolean accepted =
          event.outcome() == Outcome.ACCEPTED
              && event.explicitAcceptance()
              && call.requestedTime.equals(event.acceptedTime())
              && call.additionalCostKrw == event.additionalCostKrw();
      boolean rejected =
          event.outcome() == Outcome.REJECTED
              && !event.explicitAcceptance()
              && event.acceptedTime() == null
              && event.additionalCostKrw() == 0;
      call.status =
          accepted ? CallStatus.ACCEPTED : rejected ? CallStatus.REJECTED : CallStatus.UNKNOWN;
      call.resultReason =
          accepted
              ? "EXPLICIT_MATCHING_ACCEPTANCE"
              : rejected ? "EXPLICIT_REJECTION" : "UNCONFIRMED_OR_MISMATCH";
      Reservation r = getReservation(actor, call.reservationId);
      if (call.purpose == Purpose.BOOKING) {
        r.status =
            accepted
                ? ReservationStatus.CONFIRMED
                : rejected ? ReservationStatus.REJECTED : ReservationStatus.UNKNOWN;
        if (accepted) r.confirmedTime = event.acceptedTime();
      } else {
        if (accepted) {
          r.confirmedTime = event.acceptedTime();
          r.totalAdditionalCostKrw += event.additionalCostKrw();
        }
        // UNKNOWN retains both the old confirmation and the unresolved change reference.
        if (accepted || rejected) r.pendingChangeCallId = null;
      }
      store.save("reservations", r.id, r);
    }
    store.save("calls", call.id, call);
    return call;
  }

  private CallTask createCall(
      Search s, String candidateId, String reservationId, Purpose purpose, Instant time, int cost) {
    CallTask c = new CallTask();
    c.id = id();
    c.searchId = s.id;
    c.candidateId = candidateId;
    c.reservationId = reservationId;
    c.purpose = purpose;
    c.requestedTime = time;
    c.createdAt = clock.instant();
    c.additionalCostKrw = cost;
    calls.prepare(c);
    store.insertCall(c);
    return c;
  }

  private Candidate candidate(Search s, String id) {
    return s.candidates.stream()
        .filter(c -> c.id.equals(id))
        .findFirst()
        .orElseThrow(() -> ApiError.bad("INVALID_CANDIDATE", "검색 결과에 없는 후보입니다."));
  }

  private void requireFuture(Instant time) {
    if (!time.isAfter(clock.instant()) || time.isAfter(clock.instant().plusSeconds(86400)))
      throw ApiError.bad("INVALID_TIME", "요청 시각은 현재 이후 24시간 이내여야 합니다.");
  }

  private String id() {
    return UUID.randomUUID().toString();
  }
}
