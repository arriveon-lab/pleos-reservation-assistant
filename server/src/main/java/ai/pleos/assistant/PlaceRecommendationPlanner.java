package ai.pleos.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Listing-only, transient shortlist and inquiry plan. Does not fetch, persist, call, authorize or
 * manufacture business facts. The fact-aware product workflow remains in RecommendationEngine.
 */
public final class PlaceRecommendationPlanner {
  public enum State {
    RESULTS,
    EMPTY,
    FILTERED,
    FAILED
  }

  public enum Qualification {
    NEEDS_CONFIRMATION
  }

  public enum Reason {
    WITHIN_SEARCH_WINDOW,
    PREFERRED_CUISINE_MATCH
  }

  public enum MissingInformation {
    CUISINE,
    ADDRESS,
    PHONE
  }

  public enum Field {
    CHILD_FRIENDLY("childFriendly"),
    PARKING("parking"),
    HIGH_CHAIR("highChair"),
    STEP_FREE_ACCESS("stepFreeAccess"),
    ARRIVAL_WAIT_MINUTES("arrivalWaitMinutes"),
    RESERVATION_AVAILABLE("reservationAvailable");
    private final String key;

    Field(String key) {
      this.key = key;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String key() {
      return key;
    }
  }

  public record Requirements(
      Model.Party party,
      int maxWaitMinutes,
      Set<String> requiredFeatures,
      Set<String> preferredCuisines) {
    public Requirements {
      if (party == null
          || party.adults() < 1
          || party.adults() > 20
          || party.children() < 0
          || party.children() > 10
          || maxWaitMinutes < 0
          || maxWaitMinutes > 120
          || requiredFeatures == null
          || preferredCuisines == null
          || requiredFeatures.stream().anyMatch(Objects::isNull)
          || !Set.of("childFriendly", "parking", "highChair", "stepFreeAccess")
              .containsAll(requiredFeatures)
          || preferredCuisines.size() > 8
          || preferredCuisines.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 30))
        throw new IllegalArgumentException("Invalid recommendation requirements");
      requiredFeatures = Set.copyOf(requiredFeatures);
      preferredCuisines = Set.copyOf(preferredCuisines);
    }
  }

  public record Question(
      Field field,
      Model.Scope scope,
      Model.Certainty certainty,
      boolean arrivalTimeRequired,
      String prompt) {}

  /**
   * No provider ID, contact value or coordinates; ordinal is local to this result, not a booking
   * ID.
   */
  public record Candidate(
      int ordinal,
      String name,
      String cuisine,
      String address,
      int straightLineDistanceMeters,
      Qualification qualification,
      boolean bookable,
      boolean inquiryDispatchAllowed,
      List<Reason> reasons,
      List<MissingInformation> missingInformation,
      List<Question> questions) {
    public Candidate {
      reasons = List.copyOf(reasons);
      missingInformation = List.copyOf(missingInformation);
      questions = List.copyOf(questions);
    }
  }

  public record Plan(
      State state,
      PlaceSummary.Provider provider,
      Requirements requirements,
      Instant listingFetchedAt,
      Instant displayValidUntil,
      int scannedRecords,
      int rejectedRecords,
      int duplicateRecords,
      int outsideWindowRecords,
      PlaceSearchFailure failure,
      List<Candidate> candidates) {
    public Plan {
      candidates = List.copyOf(candidates);
    }
  }

  private record Ranked(PlaceSummary place, double distance, boolean preferred) {}

  private final Clock clock;
  private static final Duration DISPLAY_AGE = Duration.ofMinutes(5);

  public PlaceRecommendationPlanner(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public Plan evaluate(
      PlaceSummary.Provider provider,
      PlaceQuery query,
      PlaceSearchResult result,
      Requirements requirements) {
    Objects.requireNonNull(provider);
    Objects.requireNonNull(query);
    Objects.requireNonNull(requirements);
    if (result instanceof PlaceSearchResult.Failure failure)
      return failed(provider, requirements, failure.kind());
    if (!(result instanceof PlaceSearchResult.Success success))
      return failed(provider, requirements, PlaceSearchFailure.INVALID_RESPONSE);
    Instant now = clock.instant();
    if (success.fetchedAt().isAfter(now)
        || !success.fetchedAt().isAfter(now.minus(DISPLAY_AGE))
        || success.places().size() > query.limit()
        || (success.places().isEmpty() && success.rejectedRecords() > 0)
        || success.places().stream().anyMatch(p -> p.provider() != provider))
      return failed(provider, requirements, PlaceSearchFailure.INVALID_RESPONSE);

    var distinct = new LinkedHashMap<String, PlaceSummary>();
    int duplicates = 0;
    for (PlaceSummary p : success.places()) {
      var previous = distinct.putIfAbsent(p.providerPlaceId(), p);
      if (previous != null) {
        if (!previous.equals(p))
          return failed(provider, requirements, PlaceSearchFailure.INVALID_RESPONSE);
        duplicates++;
      }
    }
    var ranked = new ArrayList<Ranked>();
    int outside = 0;
    for (PlaceSummary p : distinct.values()) {
      double distance = distance(query, p);
      // Same diagnostic allowance as REC-07; straight-line geometry, never a driving ETA.
      if (distance > query.radiusMeters() + 30) {
        outside++;
        continue;
      }
      ranked.add(
          new Ranked(
              p,
              distance,
              p.cuisine() != null && requirements.preferredCuisines().contains(p.cuisine())));
    }
    ranked.sort(
        Comparator.comparing(Ranked::preferred)
            .reversed()
            .thenComparingDouble(Ranked::distance)
            .thenComparing(p -> p.place().providerPlaceId()));
    var candidates = new ArrayList<Candidate>();
    for (Ranked item : ranked.subList(0, Math.min(3, ranked.size()))) {
      PlaceSummary p = item.place();
      var reasons = new ArrayList<Reason>();
      reasons.add(Reason.WITHIN_SEARCH_WINDOW);
      if (item.preferred()) reasons.add(Reason.PREFERRED_CUISINE_MATCH);
      var missing = new ArrayList<MissingInformation>();
      if (p.cuisine() == null) missing.add(MissingInformation.CUISINE);
      if (p.address() == null) missing.add(MissingInformation.ADDRESS);
      if (p.phone() == null) missing.add(MissingInformation.PHONE);
      candidates.add(
          new Candidate(
              candidates.size() + 1,
              p.name(),
              p.cuisine(),
              p.address(),
              (int) Math.round(item.distance()),
              Qualification.NEEDS_CONFIRMATION,
              false,
              false,
              reasons,
              missing,
              questions(requirements)));
    }
    State state =
        success.places().isEmpty()
            ? State.EMPTY
            : candidates.isEmpty() ? State.FILTERED : State.RESULTS;
    return new Plan(
        state,
        provider,
        requirements,
        success.fetchedAt(),
        success.fetchedAt().plus(DISPLAY_AGE),
        success.places().size(),
        success.rejectedRecords(),
        duplicates,
        outside,
        null,
        candidates);
  }

  private static List<Question> questions(Requirements requirements) {
    var fields = new TreeSet<>(requirements.requiredFeatures());
    if (requirements.party().children() > 0) fields.add("childFriendly");
    var questions = new ArrayList<Question>();
    for (String field : fields) {
      Field questionField =
          switch (field) {
            case "parking" -> Field.PARKING;
            case "childFriendly" -> Field.CHILD_FRIENDLY;
            case "highChair" -> Field.HIGH_CHAIR;
            case "stepFreeAccess" -> Field.STEP_FREE_ACCESS;
            default -> throw new IllegalArgumentException("Unsupported recommendation feature");
          };
      String prompt =
          switch (questionField) {
            case PARKING -> "이 지점에서 주차가 가능한가요?";
            case CHILD_FRIENDLY -> "아동 동반 이용이 가능한가요?";
            case HIGH_CHAIR -> "아기의자가 준비되어 있나요? 필요한 수량도 확인해 주세요.";
            case STEP_FREE_ACCESS -> "입구부터 좌석까지 계단 없이 이동할 수 있나요? 경사로·엘리베이터 동선을 확인해 주세요.";
            default -> throw new IllegalArgumentException("Unsupported recommendation question");
          };
      questions.add(
          new Question(questionField, Model.Scope.GENERAL, Model.Certainty.UNKNOWN, false, prompt));
    }
    String party =
        "성인 " + requirements.party().adults() + "명, 아동 " + requirements.party().children() + "명";
    questions.add(
        new Question(
            Field.ARRIVAL_WAIT_MINUTES,
            Model.Scope.ARRIVAL,
            Model.Certainty.UNKNOWN,
            true,
            "도착 예정 시각 기준으로 "
                + party
                + "의 예상 대기는 몇 분인가요? 최대 "
                + requirements.maxWaitMinutes()
                + "분 이내인지 확인해 주세요."));
    questions.add(
        new Question(
            Field.RESERVATION_AVAILABLE,
            Model.Scope.ARRIVAL,
            Model.Certainty.UNKNOWN,
            true,
            "도착 예정 시각에 " + party + "의 예약이 가능한가요? 예약 가능 여부만 확인해 주세요."));
    return List.copyOf(questions);
  }

  private static Plan failed(
      PlaceSummary.Provider provider, Requirements requirements, PlaceSearchFailure failure) {
    return new Plan(
        State.FAILED, provider, requirements, null, null, 0, 0, 0, 0, failure, List.of());
  }

  static double distance(PlaceQuery query, PlaceSummary place) {
    double lat1 = Math.toRadians(query.latitude()), lat2 = Math.toRadians(place.latitude());
    double lat = Math.sin((lat2 - lat1) / 2);
    double lon = Math.sin(Math.toRadians(place.longitude() - query.longitude()) / 2);
    return 6_371_000
        * 2
        * Math.asin(
            Math.sqrt(Math.min(1, lat * lat + Math.cos(lat1) * Math.cos(lat2) * lon * lon)));
  }
}
