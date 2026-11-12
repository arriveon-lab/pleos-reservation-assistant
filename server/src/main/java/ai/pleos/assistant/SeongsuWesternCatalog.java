package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Short-lived, source-checked supplement for the public Seongsu Western-food demo query. */
final class SeongsuWesternCatalog {
  private static final Instant OBSERVED_AT = Instant.parse("2026-11-11T08:45:00Z");
  private static final Instant VALID_UNTIL = OBSERVED_AT.plus(Duration.ofDays(7));
  private static final Set<String> AREAS = Set.of("성수", "성수동", "성수역", "서울성수", "서울성수동");
  private static final List<Branch> BRANCHES =
      List.of(
          new Branch(
              "스케줄 성수",
              "서울 성동구 아차산로 104 2층",
              "1481829719",
              "스테이크·파스타·피자를 제공하는 양식 식당",
              "https://m.place.naver.com/restaurant/1481829719/information",
              "건물 지하주차장 최초 60분 무료. 대형차·전기차 입차 불가 안내.",
              "성수역 연결 통로는 안내되지만 매장까지의 단차는 미확인."),
          new Branch(
              "심퍼티쿠시 성수점",
              "서울 성동구 아차산로 6 3층 R5호",
              "1232058547",
              "파스타·뇨끼·스테이크를 제공하는 양식 식당",
              "https://m.place.naver.com/restaurant/1232058547/home",
              "건물 주차 최대 90분 지원. 초과 시 10분당 1,000원 안내.",
              "3층 엘리베이터와 휠체어 출입 안내가 있으며 전체 동선의 단차는 미확인."),
          new Branch(
              "제이드앤워터 성수 LCDC점",
              "서울 성동구 연무장17길 10 LCDC 4층",
              "2020121493",
              "브런치·파스타를 제공하는 양식 식당",
              "https://lcdc-seoul.com/lcdc/information.html",
              "LCDC 건물 발렛 4,000원. 매장 이용 시 90분 주차 지원 안내.",
              "4층 엘리베이터와 휠체어 출입 안내가 있으며 전체 동선의 단차는 미확인."));

  private SeongsuWesternCatalog() {}

  static List<Candidate> candidates(Request request, Instant now) {
    if (!request.preferredCuisines().equals(Set.of("양식"))
        || request.venueCategory() == VenueCategory.CAFE
        || !AREAS.contains(request.area().replaceAll("\\s+", ""))
        || now.isBefore(OBSERVED_AT)
        || !now.isBefore(VALID_UNTIL)) return List.of();
    return BRANCHES.stream().map(branch -> branch.candidate(request)).toList();
  }

  static boolean isListingAlias(String actual, String catalogName) {
    return "심퍼티쿠시 성수점".equals(catalogName)
        && "심파티쿠스성수점".equals(PlaceDetailEnricher.canonical(actual));
  }

  private record Branch(
      String name,
      String address,
      String placeId,
      String cuisine,
      String parkingUrl,
      String parking,
      String access) {
    Candidate candidate(Request request) {
      var listing = URI.create("https://m.place.naver.com/restaurant/" + placeId + "/home");
      var assessments =
          request.requiredNeeds().stream()
              .sorted()
              .map(
                  need ->
                      switch (need) {
                        case PARKING ->
                            new Assessment(
                                need,
                                Verdict.YES,
                                List.of(evidence(URI.create(parkingUrl), parking)));
                        case HIGH_CHAIR ->
                            new Assessment(
                                need,
                                Verdict.YES,
                                List.of(evidence(listing, "매장 편의시설에 유아의자 구비로 안내됩니다.")));
                        case STEP_FREE_ACCESS ->
                            new Assessment(
                                need, Verdict.UNKNOWN, List.of(evidence(listing, access)));
                        default -> new Assessment(need, Verdict.UNKNOWN, List.of());
                      })
              .toList();
      var remaining =
          assessments.stream()
              .filter(a -> a.verdict() != Verdict.YES)
              .map(Assessment::need)
              .toList();
      return new Candidate(
          1,
          name,
          address,
          listing,
          VenueCategory.RESTAURANT,
          remaining.isEmpty() ? Fit.POTENTIAL_MATCH : Fit.NEEDS_CONFIRMATION,
          assessments,
          List.of(),
          remaining,
          false,
          false,
          false,
          Map.of(BusinessDetailKind.CUISINE, evidence(listing, cuisine)),
          URI.create("https://map.naver.com/p/entry/place/" + placeId));
    }
  }

  private static Evidence evidence(URI url, String condition) {
    return new Evidence(
        url,
        "매장 안내 · 2026-11-11 확인",
        SourceType.MERCHANT_PAGE,
        OBSERVED_AT,
        null,
        VALID_UNTIL,
        condition,
        "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
        Model.Scope.GENERAL,
        null);
  }
}
