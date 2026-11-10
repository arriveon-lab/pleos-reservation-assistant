package ai.pleos.assistant;

import static ai.pleos.assistant.PlaceDetailEnricherTest.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlaceDetailRecommendationServiceTest {
  @Test
  void fictional_demo_keeps_general_website_details_separate_from_arrival_and_authority()
      throws Exception {
    var demo = PlaceDetailDemo.sample(java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    assertThat(demo.mode()).isEqualTo("MOCK");
    assertThat(demo.sourceDiscovery().state()).isEqualTo(GptPlaceSourceDiscovery.State.SOURCES);
    assertThat(demo.sourceDiscovery().matchedMerchant()).isFalse();
    assertThat(demo.enrichment().candidateDetails()).hasSize(3);
    assertThat(demo.enrichment().candidateDetails())
        .anySatisfy(
            candidate -> {
              assertThat(candidate.detailReport().outcome())
                  .isEqualTo(PlaceDetailEnricher.Outcome.DETAILS);
              assertThat(candidate.detailReport().remainingQuestions())
                  .contains(
                      "CHILD_FRIENDLY", "ARRIVAL_WAIT_MINUTES", "ARRIVAL_RESERVATION_AVAILABLE")
                  .doesNotContain("PARKING");
              assertThat(candidate.detailReport().bookable()).isFalse();
            });
  }

  private final Time clock = new Time();
  private final PlaceQuery query = new PlaceQuery(37.544, 127.055, 1000, 15);
  private final PlaceRecommendationPlanner.Requirements requirements =
      new PlaceRecommendationPlanner.Requirements(
          new Model.Party(2, 1), 15, Set.of("parking"), Set.of("한식"));

  private static PlaceSummary place(String id, String name) {
    return new PlaceSummary(
        PlaceSummary.Provider.MOCK,
        id,
        name,
        "한식",
        37.544,
        127.055,
        ID.address(),
        "fictional-contact",
        null,
        null);
  }

  private PlaceDetailRecommendationService service(PlaceDetailEnricher.PageAdapter pages) {
    return new PlaceDetailRecommendationService(clock, PlaceDetailEnricher.Mode.MOCK, pages);
  }

  @Test
  void approved_public_identity_can_use_approved_web_source_but_kakao_cannot() {
    var calls = new AtomicInteger();
    var svc =
        new PlaceDetailRecommendationService(
            clock,
            PlaceDetailEnricher.Mode.READ_ONLY_WEB,
            (source, deadline) -> {
              calls.incrementAndGet();
              return new PlaceDetailEnricher.Page(
                  source.url(),
                  html(business()),
                  NOW.minusSeconds(60),
                  PlaceDetailEnricher.Mode.READ_ONLY_WEB);
            });
    var publicPlace =
        new PlaceSummary(
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            "fictional-public-id",
            ID.name(),
            null,
            37.544,
            127.055,
            ID.address(),
            "fictional-contact",
            null,
            null);
    var publicResult =
        svc.evaluate(
            1,
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            query,
            new PlaceSearchResult.Success(List.of(publicPlace), NOW, 0),
            requirements,
            Map.of("fictional-public-id", List.of(A)),
            () -> true);
    assertThat(calls).hasValue(1);
    assertThat(publicResult.candidateDetails().getFirst().detailReport().outcome())
        .isEqualTo(PlaceDetailEnricher.Outcome.DETAILS);
    assertThat(publicResult.candidateDetails().getFirst().detailReport().bookable()).isFalse();

    var kakaoPlace =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            "fictional-kakao-id",
            ID.name(),
            null,
            37.544,
            127.055,
            ID.address(),
            "fictional-contact",
            null,
            null);
    var kakaoResult =
        svc.evaluate(
            2,
            PlaceSummary.Provider.KAKAO,
            query,
            new PlaceSearchResult.Success(List.of(kakaoPlace), NOW, 0),
            requirements,
            Map.of("fictional-kakao-id", List.of(A)),
            () -> true);
    assertThat(calls).hasValue(1);
    assertThat(kakaoResult.candidateDetails().getFirst().detailReport().outcome())
        .isEqualTo(PlaceDetailEnricher.Outcome.UNKNOWN);
    assertThat(kakaoResult.candidateDetails().getFirst().detailReport().reasons())
        .contains(PlaceDetailEnricher.Reason.DATA_USE_BLOCKED);
  }

  @Test
  void binds_at_most_three_selected_ordinals_to_details_without_contact_or_provider_ids()
      throws Exception {
    var calls = new AtomicInteger();
    var svc =
        service(
            (s, d) -> {
              calls.incrementAndGet();
              return new PlaceDetailEnricher.Page(
                  s.url(), html(business()), NOW.minusSeconds(60), PlaceDetailEnricher.Mode.MOCK);
            });
    var places =
        List.of(
            place("private-1", ID.name()),
            place("private-2", "가상식당 2"),
            place("private-3", "가상식당 3"),
            place("private-4", "가상식당 4"));
    var result =
        svc.evaluate(
            42,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(places, NOW, 0),
            requirements,
            Map.of("private-1", List.of(A)),
            () -> true);
    assertThat(result.state()).isEqualTo(PlaceDetailRecommendationService.State.READY);
    assertThat(result.candidateDetails())
        .hasSize(3)
        .extracting(PlaceDetailRecommendationService.CandidateDetail::ordinal)
        .containsExactly(1, 2, 3);
    assertThat(result.displayValidUntil()).isEqualTo(NOW.plusSeconds(240));
    assertThat(result.candidateDetails().getFirst().detailReport().remainingQuestions())
        .contains("CHILD_FRIENDLY", "PARKING", "ARRIVAL_RESERVATION_AVAILABLE");
    assertThat(JSON.findAndRegisterModules().writeValueAsString(result))
        .doesNotContain("private-1", "private-4", "fictional-contact", "providerPlaceId");
    assertThat(calls).hasValue(1);
  }

  @Test
  void ambiguous_same_name_address_is_not_assigned_to_either_provider_record() {
    var calls = new AtomicInteger();
    var svc =
        service(
            (s, d) -> {
              calls.incrementAndGet();
              throw new AssertionError("ambiguous");
            });
    var result =
        svc.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(
                List.of(place("a", ID.name()), place("b", ID.name())), NOW, 0),
            requirements,
            Map.of("a", List.of(A), "b", List.of(B)),
            () -> true);
    assertThat(calls).hasValue(0);
    assertThat(result.candidateDetails())
        .allSatisfy(
            c -> {
              assertThat(c.detailReport().outcome()).isEqualTo(PlaceDetailEnricher.Outcome.UNKNOWN);
              assertThat(c.detailReport().remainingQuestions())
                  .contains("PARKING", "CHILD_FRIENDLY");
            });
  }

  @Test
  void destination_change_during_page_fetch_hides_entire_plan() {
    var current = new AtomicBoolean(true);
    var svc =
        service(
            (s, d) -> {
              current.set(false);
              return new PlaceDetailEnricher.Page(
                  s.url(), html(business()), NOW, PlaceDetailEnricher.Mode.MOCK);
            });
    var result =
        svc.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(List.of(place("a", ID.name())), NOW, 0),
            requirements,
            Map.of("a", List.of(A)),
            current::get);
    assertThat(result.state()).isEqualTo(PlaceDetailRecommendationService.State.CANCELLED);
    assertThat(result.recommendationPlan()).isNull();
    assertThat(result.displayValidUntil()).isNull();
    assertThat(result.candidateDetails()).isEmpty();
  }

  @Test
  void late_fetch_hides_entire_plan() {
    var svc =
        service(
            (s, d) -> {
              clock.now = NOW.plusSeconds(300);
              return new PlaceDetailEnricher.Page(
                  s.url(), html(business()), NOW, PlaceDetailEnricher.Mode.MOCK);
            });
    var result =
        svc.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(List.of(place("a", ID.name())), NOW, 0),
            requirements,
            Map.of("a", List.of(A)),
            () -> true);
    assertThat(result.state()).isEqualTo(PlaceDetailRecommendationService.State.EXPIRED);
    assertThat(result.recommendationPlan()).isNull();
    assertThat(result.candidateDetails()).isEmpty();
  }

  @Test
  void empty_and_failed_listings_do_not_fetch_details() {
    var svc =
        service(
            (s, d) -> {
              throw new AssertionError("not expected");
            });
    var empty =
        svc.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(List.of(), NOW, 0),
            requirements,
            Map.of(),
            () -> true);
    assertThat(empty.recommendationPlan().state())
        .isEqualTo(PlaceRecommendationPlanner.State.EMPTY);
    assertThat(empty.candidateDetails()).isEmpty();
    var failed =
        svc.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE),
            requirements,
            Map.of(),
            () -> true);
    assertThat(failed.state()).isEqualTo(PlaceDetailRecommendationService.State.FAILED);
    assertThat(failed.recommendationPlan()).isNull();
  }
}
