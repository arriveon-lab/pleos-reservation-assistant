package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DestinationRecommendationServiceTest {
  @Test
  void resolves_web_identified_branches_when_category_first_page_does_not_contain_them() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    class Lookup implements PlaceSearchAdapter, PlaceIdentityLookup {
      int queries;

      @Override
      public PlaceSearchResult search(PlaceQuery query) {
        return new PlaceSearchResult.Success(
            java.util.List.of(
                new PlaceSummary(
                    PlaceSummary.Provider.KAKAO,
                    "nearby",
                    "근처 다른 식당",
                    null,
                    37.544,
                    127.055,
                    "다른 주소",
                    "0212345678",
                    null,
                    null)),
            clock.instant(),
            0);
      }

      @Override
      public PlaceSearchResult lookup(
          PlaceQuery query,
          java.util.List<Identity> identities,
          java.util.function.BooleanSupplier current) {
        queries++;
        assertThat(identities).hasSize(3);
        assertThat(identities).allSatisfy(i -> assertThat(i.name()).startsWith("가상 식당"));
        return new PlaceSearchResult.Success(
            java.util.stream.IntStream.range(0, 3)
                .mapToObj(
                    n ->
                        new PlaceSummary(
                            PlaceSummary.Provider.KAKAO,
                            "resolved-" + n,
                            "가상 식당 " + n,
                            null,
                            37.544,
                            127.055,
                            "가상 주소 " + n,
                            "0212345678",
                            null,
                            null))
                .toList(),
            clock.instant(),
            0);
      }
    }
    var places = new Lookup();
    DestinationRecommendationService.WebGateway web =
        (request, deadline, current) ->
            new WebRecommendation.Result(
                WebRecommendation.Mode.WEB_SEARCH,
                WebRecommendation.State.RESULTS,
                clock.instant(),
                clock.instant().plusSeconds(240),
                java.util.stream.IntStream.range(0, 3)
                    .mapToObj(
                        n ->
                            new WebRecommendation.Candidate(
                                n + 1,
                                "가상 식당 " + n,
                                "가상 주소 " + n,
                                URI.create("https://example.org/fictional-" + n),
                                WebRecommendation.Fit.NEEDS_CONFIRMATION,
                                java.util.List.of(
                                    new WebRecommendation.Assessment(
                                        WebRecommendation.Need.PARKING,
                                        WebRecommendation.Verdict.UNKNOWN,
                                        java.util.List.of())),
                                java.util.List.of(WebRecommendation.Need.PARKING),
                                false,
                                false,
                                false))
                    .toList(),
                0);
    try (var service =
        new DestinationRecommendationService(
            places,
            PlaceSummary.Provider.KAKAO,
            new DestinationRecommendationService.SourceInfo(
                PlaceSummary.Provider.KAKAO, null, null, null, "Kakao Local", null, null),
            clock,
            Runnable::run,
            web)) {
      var id =
          service.start(
              "owner-a",
              UUID.randomUUID(),
              1,
              "가상 서울숲",
              new PlaceQuery(37.544, 127.055, 1000, 15),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 0), 15, Set.of("parking"), Set.of()));
      var result = service.poll("owner-a", id).orElseThrow().result();
      assertThat(result.listing().candidates()).hasSize(1);
      assertThat(result.matches().candidates()).hasSize(3);
      assertThat(result.matches().candidates())
          .allSatisfy(candidate -> assertThat(candidate.bookable()).isFalse());
      assertThat(places.queries).isEqualTo(1);
    }
  }

  @Test
  void independent_web_reports_join_three_kakao_candidates_without_sending_kakao_data() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    PlaceSearchAdapter places =
        query ->
            new PlaceSearchResult.Success(
                java.util.stream.IntStream.range(0, 3)
                    .mapToObj(
                        n ->
                            new PlaceSummary(
                                PlaceSummary.Provider.KAKAO,
                                "private-kakao-" + n,
                                "가상 식당 " + n,
                                null,
                                37.544,
                                127.055,
                                "가상 주소 " + n,
                                "0212345678",
                                null,
                                null))
                    .toList(),
                clock.instant(),
                0);
    DestinationRecommendationService.WebGateway web =
        (request, deadline, current) -> {
          assertThat(request.area()).isEqualTo("가상 서울숲");
          assertThat(request.requiredNeeds())
              .containsExactlyInAnyOrder(
                  WebRecommendation.Need.PARKING, WebRecommendation.Need.HIGH_CHAIR);
          assertThat(request.toString()).doesNotContain("private-kakao", "0212345678");
          var candidates =
              java.util.stream.IntStream.range(0, 3)
                  .mapToObj(
                      n ->
                          new WebRecommendation.Candidate(
                              n + 1,
                              "가상 식당 " + n,
                              "가상 주소 " + n,
                              URI.create("https://example.org/fictional-" + n),
                              WebRecommendation.Fit.NEEDS_CONFIRMATION,
                              java.util.List.of(
                                  new WebRecommendation.Assessment(
                                      WebRecommendation.Need.PARKING,
                                      WebRecommendation.Verdict.UNKNOWN,
                                      java.util.List.of()),
                                  new WebRecommendation.Assessment(
                                      WebRecommendation.Need.HIGH_CHAIR,
                                      WebRecommendation.Verdict.UNKNOWN,
                                      java.util.List.of())),
                              java.util.List.of(
                                  WebRecommendation.Need.PARKING,
                                  WebRecommendation.Need.HIGH_CHAIR),
                              false,
                              false,
                              false))
                  .toList();
          return new WebRecommendation.Result(
              WebRecommendation.Mode.WEB_SEARCH,
              WebRecommendation.State.RESULTS,
              clock.instant(),
              clock.instant().plusSeconds(240),
              candidates,
              0);
        };
    try (var service =
        new DestinationRecommendationService(
            places,
            PlaceSummary.Provider.KAKAO,
            new DestinationRecommendationService.SourceInfo(
                PlaceSummary.Provider.KAKAO, null, null, null, "Kakao Local", null, null),
            clock,
            Runnable::run,
            web)) {
      var id =
          service.start(
              "owner-a",
              UUID.randomUUID(),
              1,
              "가상 서울숲",
              new PlaceQuery(37.544, 127.055, 1000, 15),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 1), 15, Set.of("parking", "highChair"), Set.of()));
      var result = service.poll("owner-a", id).orElseThrow().result();
      assertThat(result.webState()).isEqualTo(WebRecommendation.State.RESULTS);
      assertThat(result.matches().state()).isEqualTo(WebListingRecommendationJoiner.State.RESULTS);
      assertThat(result.matches().candidates()).hasSize(3);
      assertThat(result.matches().candidates()).allSatisfy(c -> assertThat(c.bookable()).isFalse());
    }
  }

  @Test
  void kakao_source_uses_transient_listing_without_returning_phone_or_booking_authority() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    PlaceSearchAdapter adapter =
        query ->
            new PlaceSearchResult.Success(
                java.util.stream.IntStream.range(0, 3)
                    .mapToObj(
                        n ->
                            new PlaceSummary(
                                PlaceSummary.Provider.KAKAO,
                                "kakao-" + n,
                                "가상 식당 " + n,
                                null,
                                37.544,
                                127.055,
                                "가상 주소 " + n,
                                "0212345678",
                                null,
                                null))
                    .toList(),
                clock.instant(),
                0);
    try (var service =
        new DestinationRecommendationService(
            adapter,
            PlaceSummary.Provider.KAKAO,
            new DestinationRecommendationService.SourceInfo(
                PlaceSummary.Provider.KAKAO, null, null, null, "Kakao Local", null, null),
            clock,
            Runnable::run)) {
      var id =
          service.start(
              "owner-a",
              UUID.randomUUID(),
              1,
              "가상 서울숲",
              new PlaceQuery(37.544, 127.055, 1000, 15),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 0), 15, Set.of("parking"), Set.of()));
      var plan = service.poll("owner-a", id).orElseThrow().result().listing();
      assertThat(plan.provider()).isEqualTo(PlaceSummary.Provider.KAKAO);
      assertThat(plan.candidates()).hasSize(3);
      assertThat(plan.toString()).doesNotContain("0212345678", "kakao-");
      assertThat(plan.candidates()).allSatisfy(c -> assertThat(c.bookable()).isFalse());
    }
  }

  @Test
  void destination_outside_public_pilot_is_failure_not_empty_recommendations() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    var catalog =
        SeoulPlaceCatalog.load(
            Path.of("src/test/resources/places/seoul-catalog-fictional.json"),
            new ObjectMapper().findAndRegisterModules());
    try (var service =
        new DestinationRecommendationService(
            new SeoulPlaceSearchAdapter(catalog, clock), clock, Runnable::run)) {
      UUID task =
          service.start(
              "owner-a",
              UUID.randomUUID(),
              1,
              "가상 서울숲",
              new PlaceQuery(37.50, 127.00, 1000, 3),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 0), 15, Set.of("parking"), Set.of()));
      var plan = service.poll("owner-a", task).orElseThrow().result().listing();
      assertThat(plan.state()).isEqualTo(PlaceRecommendationPlanner.State.FAILED);
      assertThat(plan.failure()).isEqualTo(PlaceSearchFailure.INVALID_QUERY);
      assertThat(plan.candidates()).isEmpty();
    }
  }

  @Test
  void fictional_public_catalog_shortlist_is_polled_once_and_cannot_be_booked() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    var catalog =
        SeoulPlaceCatalog.load(
            Path.of("src/test/resources/places/seoul-catalog-fictional.json"),
            new ObjectMapper().findAndRegisterModules());
    var queue = new ArrayDeque<Runnable>();
    try (var service =
        new DestinationRecommendationService(
            new SeoulPlaceSearchAdapter(catalog, clock), clock, queue::add)) {
      UUID view = UUID.randomUUID();
      var requirements =
          new PlaceRecommendationPlanner.Requirements(
              new Model.Party(2, 1), 15, Set.of("parking"), Set.of());
      UUID task =
          service.start(
              "owner-a", view, 1, "가상 서울숲", new PlaceQuery(37.544, 127.055, 1000, 3), requirements);
      assertThat(service.poll("owner-a", task).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.QUEUED);
      assertThat(service.poll("owner-b", task)).isEmpty();
      queue.remove().run();
      var snapshot = service.poll("owner-a", task).orElseThrow();
      assertThat(snapshot.state()).isEqualTo(RecommendationJobRegistry.State.RESULTS);
      assertThat(snapshot.result().listing().provider())
          .isEqualTo(PlaceSummary.Provider.SEOUL_OPEN_DATA);
      assertThat(snapshot.result().listing().candidates()).hasSize(3);
      assertThat(snapshot.result().listing().candidates())
          .allSatisfy(
              candidate -> {
                assertThat(candidate.bookable()).isFalse();
                assertThat(candidate.inquiryDispatchAllowed()).isFalse();
                assertThat(candidate.qualification())
                    .isEqualTo(PlaceRecommendationPlanner.Qualification.NEEDS_CONFIRMATION);
              });
      assertThat(service.poll("owner-a", task).orElseThrow().result()).isSameAs(snapshot.result());
      assertThat(queue).isEmpty();
      service.invalidate("owner-a", view);
      assertThat(service.poll("owner-a", task).orElseThrow().result()).isNull();
    }
  }
}
