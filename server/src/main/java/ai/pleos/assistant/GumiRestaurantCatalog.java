package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Source-linked snapshot for the public Gumi campus address used in the phone prototype. */
final class GumiRestaurantCatalog {
  private static final Pattern CAMPUS_ADDRESS =
      Pattern.compile("(?:경상북도|경북)?구미시?3공단3로302(?:\\(임수동\\))?");

  // Coordinates of the parking tower at the campus address, not a live user location.
  private static final PlaceQuery CAMPUS = new PlaceQuery(36.10711, 128.41183, 2_000, 3);
  private static final Instant OBSERVED_AT = Instant.parse("2026-11-11T07:15:00Z");
  private static final Instant VALID_UNTIL = OBSERVED_AT.plus(Duration.ofDays(30));

  private static final List<Restaurant> RESTAURANTS =
      List.of(
          new Restaurant(
              128,
              "36641798",
              "칼잡이남자",
              "경북 구미시 인동중앙로3길 25 (황상동)",
              36.1083920847983,
              128.418898402962,
              "15:00–23:40, 마지막 주문 23:10, 일요일 휴무",
              "없음",
              "전용 주차장 없음",
              Verdict.UNKNOWN),
          new Restaurant(
              100,
              "15508064",
              "천생산아구찜",
              "경북 구미시 인동남길 102",
              36.0976437737827,
              128.426906882807,
              "11:00–21:30, 월요일 휴무",
              "평일 14:30–16:30",
              "식당 주차장 10대",
              Verdict.YES),
          new Restaurant(
              122,
              "1906589434",
              "원조옛날국밥구미점",
              "경북 구미시 인동26길 40 (진평동)",
              36.094023823047,
              128.425991821479,
              "월–금 10:00–24:00, 일 10:00–22:00, 토요일 휴무",
              "없음",
              "전용 주차장 4대",
              Verdict.YES));

  private GumiRestaurantCatalog() {}

  static Optional<Result> search(Request request, Clock clock) {
    if (!request.preferredCuisines().isEmpty()
        || request.venueCategory() == VenueCategory.CAFE
        || !CAMPUS_ADDRESS.matcher(request.area().replaceAll("\\s+", "")).matches()
        || !clock.instant().isBefore(VALID_UNTIL)) return Optional.empty();

    var nearby =
        RESTAURANTS.stream()
            .map(restaurant -> new Nearby(restaurant, restaurant.distance()))
            .filter(place -> place.distance() <= CAMPUS.radiusMeters())
            .sorted(Comparator.comparingDouble(Nearby::distance))
            .map(Nearby::restaurant)
            .toList();
    var candidates =
        java.util.stream.IntStream.range(0, nearby.size())
            .mapToObj(index -> nearby.get(index).candidate(index + 1, request))
            .toList();
    return Optional.of(
        new Result(
            Mode.WEB_SEARCH, State.RESULTS, OBSERVED_AT, VALID_UNTIL, shortlist(candidates), 0));
  }

  private record Nearby(Restaurant restaurant, double distance) {}

  private record Restaurant(
      int articleId,
      String naverPlaceId,
      String name,
      String address,
      double latitude,
      double longitude,
      String hours,
      String breakTime,
      String parking,
      Verdict parkingVerdict) {
    URI source() {
      return URI.create("https://www.gumi.go.kr/gumifood/page.do?mid=8&ArticleId=" + articleId);
    }

    double distance() {
      return PlaceRecommendationPlanner.distance(
          CAMPUS,
          new PlaceSummary(
              PlaceSummary.Provider.WEB_SEARCH,
              Integer.toString(articleId),
              name,
              null,
              latitude,
              longitude,
              address,
              null,
              null,
              null));
    }

    Candidate candidate(int ordinal, Request request) {
      var assessments =
          request.requiredNeeds().stream()
              .sorted()
              .map(
                  need ->
                      need == Need.PARKING
                          ? new Assessment(need, parkingVerdict, List.of(evidence(parking)))
                          : new Assessment(need, Verdict.UNKNOWN, List.of()))
              .toList();
      var remaining =
          assessments.stream()
              .filter(assessment -> assessment.verdict() != Verdict.YES)
              .map(Assessment::need)
              .toList();
      return new Candidate(
          ordinal,
          name,
          address,
          source(),
          VenueCategory.RESTAURANT,
          remaining.isEmpty() ? Fit.POTENTIAL_MATCH : Fit.NEEDS_CONFIRMATION,
          assessments,
          List.of(),
          remaining,
          false,
          false,
          false,
          Map.of(
              BusinessDetailKind.OPENING_HOURS,
              evidence(hours),
              BusinessDetailKind.BREAK_TIME,
              evidence(breakTime)),
          URI.create("https://map.naver.com/p/entry/place/" + naverPlaceId));
    }

    Evidence evidence(String condition) {
      return new Evidence(
          source(),
          "구미시 맛집 안내 · " + name,
          SourceType.OTHER,
          OBSERVED_AT,
          null,
          VALID_UNTIL,
          condition,
          "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
          Model.Scope.GENERAL,
          null);
    }
  }
}
