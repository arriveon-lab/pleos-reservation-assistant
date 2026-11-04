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
  private final MockCallAdapter calls;
  private final Clock clock;

  public WorkflowService(
      Store store, RecommendationEngine recommendations, MockCallAdapter calls, Clock clock) {
    this.store = store;
    this.recommendations = recommendations;
    this.calls = calls;
    this.clock = clock;
  }

  @Transactional
  public Search search(String key, SearchRequest request) {
    return store.command(
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
          s.candidates = recommendations.fixtures(request);
          recommendations.rank(s);
          store.insertSearch(s);
          return s;
        });
  }

  @Transactional(readOnly = true)
  public Search getSearch(String id) {
    Search s = store.get("searches", id, Search.class);
    recommendations.rank(s); // Expiration is evaluated on every read, not only on writes.
    return s;
  }

  @Transactional(readOnly = true)
  public Reservation getReservation(String id) {
    return store.get("reservations", id, Reservation.class);
  }

  @Transactional(readOnly = true)
  public CallTask getCall(String id) {
    return store.get("calls", id, CallTask.class);
  }

  @Transactional(readOnly = true)
  public List<CallTask> getCalls(String searchId) {
    store.get("searches", searchId, Search.class);
    return store.bySearch("calls", searchId, CallTask.class);
  }

  @Transactional
  public CallTask inquire(String searchId, String key, InquiryRequest request) {
    return store.command(
        "inquiry:" + searchId,
        key,
        request,
        CallTask.class,
        () -> {
          Search s = getSearch(searchId);
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
  public Reservation book(String searchId, String key, BookingRequest request) {
    return store.command(
        "booking:" + searchId,
        key,
        request,
        Reservation.class,
        () -> {
          Search s = getSearch(searchId);
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
          for (Reservation existing : store.byTrip(s.request.navigation().tripId())) {
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
  public Reservation change(String reservationId, String key, ChangeRequest request) {
    return store.command(
        "change:" + reservationId,
        key,
        request,
        Reservation.class,
        () -> {
          Reservation r = getReservation(reservationId);
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
                  getSearch(r.searchId),
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
  public CallTask outcome(String callId, OutcomeRequest event) {
    store.lock();
    CallTask call = getCall(callId);
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
        Search s = getSearch(call.searchId);
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
      Reservation r = getReservation(call.reservationId);
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
