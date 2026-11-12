package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebListingRecommendationJoinerTest {
  private static final Instant NOW = Instant.parse("2026-11-10T00:01:00Z");
  private static final PlaceQuery QUERY = new PlaceQuery(37.544, 127.055, 1000, 15);
  private static final Set<WebRecommendation.Need> NEEDS =
      Set.of(WebRecommendation.Need.PARKING, WebRecommendation.Need.HIGH_CHAIR);
  private final WebListingRecommendationJoiner joiner =
      new WebListingRecommendationJoiner(Clock.fixed(NOW, ZoneOffset.UTC));

  private PlaceSummary place(String id, String name, String address) {
    return new PlaceSummary(
        PlaceSummary.Provider.SEOUL_OPEN_DATA,
        id,
        name,
        null,
        37.544,
        127.055,
        address,
        null,
        null,
        null);
  }

  @Test
  void kakao_listing_can_match_independent_web_report_without_exposing_contact() {
    var kakao =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            "kakao-id",
            "가상 식당",
            null,
            37.544,
            127.055,
            "가상 주소",
            "0212345678",
            null,
            null);
    var result =
        joiner.join(
            QUERY,
            new PlaceSearchResult.Success(List.of(kakao), NOW, 0),
            PlaceSummary.Provider.KAKAO,
            webResult(web("가상 식당", "가상 주소")),
            NEEDS);
    assertThat(result.state()).isEqualTo(WebListingRecommendationJoiner.State.RESULTS);
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.toString()).doesNotContain("0212345678", "kakao-id");
  }

  private WebRecommendation.Candidate web(String name, String address) {
    var evidence =
        new WebRecommendation.Evidence(
            URI.create("https://example.org/place"),
            "가상 근거",
            WebRecommendation.SourceType.MERCHANT_PAGE,
            NOW,
            null,
            NOW.plusSeconds(240),
            "가상 지점의 주차 안내",
            "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
            Model.Scope.GENERAL,
            null);
    return new WebRecommendation.Candidate(
        1,
        name,
        address,
        URI.create("https://example.org/place"),
        WebRecommendation.Fit.NEEDS_CONFIRMATION,
        List.of(
            new WebRecommendation.Assessment(
                WebRecommendation.Need.PARKING, WebRecommendation.Verdict.YES, List.of(evidence)),
            new WebRecommendation.Assessment(
                WebRecommendation.Need.HIGH_CHAIR, WebRecommendation.Verdict.UNKNOWN, List.of())),
        List.of(WebRecommendation.Need.HIGH_CHAIR),
        false,
        false,
        false);
  }

  private WebRecommendation.Result webResult(WebRecommendation.Candidate... candidates) {
    return new WebRecommendation.Result(
        WebRecommendation.Mode.WEB_SEARCH,
        WebRecommendation.State.RESULTS,
        NOW,
        NOW.plusSeconds(240),
        List.of(candidates),
        0);
  }

  @Test
  void only_exact_public_branch_matches_and_unknown_remains_unbookable() {
    var listing =
        new PlaceSearchResult.Success(
            List.of(
                place("a", "가상 식당 A", "가상 주소 A"),
                place("b", "가상 식당 B", "가상 주소 B"),
                place("c", "가상 식당 C", "가상 주소 C")),
            NOW,
            0);
    var result =
        joiner.join(
            QUERY,
            listing,
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            webResult(
                web("가상 식당 A", "가상 주소 A"), web("가상 식당 B", "가상 주소 X"), web("가상 식당 C", "가상 주소 C")),
            NEEDS);
    assertThat(result.state()).isEqualTo(WebListingRecommendationJoiner.State.RESULTS);
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates().getFirst().name()).isEqualTo("가상 식당 A");
    assertThat(result.candidates().getLast().name()).isEqualTo("가상 식당 C");
    assertThat(result.candidates())
        .allSatisfy(
            c -> {
              assertThat(c.remainingQuestions()).contains(WebRecommendation.Need.HIGH_CHAIR);
              assertThat(c.bookable()).isFalse();
              assertThat(c.inquiryDispatchAllowed()).isFalse();
            });
  }

  @Test
  void ambiguous_public_identity_and_expired_web_are_not_promoted() {
    var duplicates =
        new PlaceSearchResult.Success(
            List.of(place("a", "가상 식당", "가상 주소"), place("b", "가상 식당", "가상 주소")), NOW, 0);
    assertThat(
            joiner
                .join(
                    QUERY,
                    duplicates,
                    PlaceSummary.Provider.SEOUL_OPEN_DATA,
                    webResult(web("가상 식당", "가상 주소")),
                    NEEDS)
                .state())
        .isEqualTo(WebListingRecommendationJoiner.State.NO_MATCH);
    var expired =
        new WebRecommendation.Result(
            WebRecommendation.Mode.WEB_SEARCH,
            WebRecommendation.State.RESULTS,
            NOW.minusSeconds(300),
            NOW,
            List.of(web("가상 식당", "가상 주소")),
            0);
    assertThat(
            joiner
                .join(QUERY, duplicates, PlaceSummary.Provider.SEOUL_OPEN_DATA, expired, NEEDS)
                .state())
        .isEqualTo(WebListingRecommendationJoiner.State.INVALID_EVIDENCE);
    assertThat(
            joiner
                .join(
                    QUERY,
                    duplicates,
                    PlaceSummary.Provider.SEOUL_OPEN_DATA,
                    webResult(web("가상 식당", "가상 주소")),
                    Set.of(WebRecommendation.Need.HIGH_CHAIR))
                .state())
        .isEqualTo(WebListingRecommendationJoiner.State.INVALID_EVIDENCE);
  }
}
