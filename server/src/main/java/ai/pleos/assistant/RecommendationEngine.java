package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RecommendationEngine {
  private final Clock clock;

  public RecommendationEngine(Clock clock) {
    this.clock = clock;
  }

  public void select(Search search) {
    rank(search);
    search.candidates =
        new ArrayList<>(search.candidates.subList(0, Math.min(3, search.candidates.size())));
  }

  public void rank(Search search) {
    Instant now = clock.instant();
    for (Candidate c : search.candidates) {
      c.missing.clear();
      c.exclusionReasons.clear();
      c.eligible = true;
      Set<String> required = new LinkedHashSet<>(search.request.requiredFeatures());
      if (search.request.party().children() > 0) required.add("childFriendly");
      for (String key : required) {
        Fact f = c.facts.get(key);
        if (!usable(f, now) || !(f.value() instanceof Boolean) || f.scope() != Scope.GENERAL)
          c.missing.add(key);
        else if (Boolean.FALSE.equals(f.value())) c.exclusionReasons.add(key);
      }
      Fact wait = c.facts.get("arrivalWaitMinutes");
      if (!arrivalFact(wait, now, search.request.navigation().eta()) || !validWait(wait.value()))
        c.missing.add("arrivalWaitMinutes");
      else if (((Number) wait.value()).intValue() > search.request.maxWaitMinutes())
        c.exclusionReasons.add("arrivalWaitMinutes");
      Fact available = c.facts.get("reservationAvailable");
      if (!arrivalFact(available, now, search.request.navigation().eta())
          || !(available.value() instanceof Boolean)) c.missing.add("reservationAvailable");
      else if (Boolean.FALSE.equals(available.value()))
        c.exclusionReasons.add("reservationAvailable");
      c.eligible = c.exclusionReasons.isEmpty();
      c.bookable = c.eligible && c.missing.isEmpty();
      c.preferenceScore =
          c.cuisine != null
                  && !c.cuisine.isBlank()
                  && search.request.preferredCuisines().contains(c.cuisine)
              ? 1
              : 0;
    }
    search.candidates.sort(
        Comparator.comparing((Candidate c) -> !c.eligible)
            .thenComparingInt(c -> c.missing.size())
            .thenComparing(Comparator.comparingInt((Candidate c) -> c.preferenceScore).reversed())
            .thenComparing(c -> c.id));
  }

  private boolean usable(Fact f, Instant now) {
    return f != null
        && f.value() != null
        && f.certainty() != null
        && f.certainty() != Certainty.UNKNOWN
        && f.mode() == Mode.MOCK
        && f.observedAt() != null
        && !f.observedAt().isAfter(now)
        && f.validUntil() != null
        && f.validUntil().isAfter(f.observedAt())
        && f.validUntil().isAfter(now);
  }

  private boolean arrivalFact(Fact fact, Instant now, Instant eta) {
    return usable(fact, now) && fact.scope() == Scope.ARRIVAL && eta.equals(fact.appliesAt());
  }

  private boolean validWait(Object value) {
    return value instanceof Number number
        && Double.isFinite(number.doubleValue())
        && number.doubleValue() >= 0
        && number.doubleValue() <= 240
        && number.doubleValue() == number.intValue();
  }

  public void apply(Candidate c, InquiryFacts f, String callId, Instant eta, Instant observedAt) {
    putObserved(
        c, "childFriendly", f.childFriendly(), callId, observedAt, observedAt, Scope.GENERAL);
    putObserved(c, "parking", f.parking(), callId, observedAt, observedAt, Scope.GENERAL);
    putObserved(c, "seatsAvailable", f.seatsAvailable(), callId, observedAt, observedAt, Scope.NOW);
    if (f.reservationAvailable() != null && !eta.equals(f.arrivalAppliesAt()))
      throw ApiError.bad("ARRIVAL_TIME_MISMATCH", "예약 가능 여부의 적용 시각이 ETA와 일치해야 합니다.");
    putObserved(
        c,
        "reservationAvailable",
        f.reservationAvailable(),
        callId,
        observedAt,
        eta,
        Scope.ARRIVAL);
    putObserved(c, "nowWaitMinutes", f.nowWaitMinutes(), callId, observedAt, observedAt, Scope.NOW);
    if (f.arrivalWaitMinutes() != null) {
      if (f.arrivalAppliesAt() == null || !eta.equals(f.arrivalAppliesAt()))
        throw ApiError.bad("ARRIVAL_TIME_MISMATCH", "도착 예상 대기의 적용 시각이 해당 ETA와 일치해야 합니다.");
      putObserved(
          c,
          "arrivalWaitMinutes",
          f.arrivalWaitMinutes(),
          callId,
          observedAt,
          f.arrivalAppliesAt(),
          Scope.ARRIVAL);
    }
  }

  private void putObserved(
      Candidate c,
      String key,
      Object value,
      String ref,
      Instant observed,
      Instant applies,
      Scope scope) {
    if (value == null) return;
    Fact previous = c.facts.get(key);
    if (previous != null && previous.observedAt().isAfter(observed)) return;
    c.facts.put(
        key,
        new Fact(
            value,
            Mode.MOCK,
            "MOCK_CALL",
            ref,
            observed,
            applies,
            observed.plusSeconds(300),
            key.equals("arrivalWaitMinutes") ? Certainty.ESTIMATED : Certainty.CONFIRMED,
            scope));
  }
}
