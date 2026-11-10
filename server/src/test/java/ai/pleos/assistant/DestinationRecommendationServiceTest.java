package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
              new PlaceQuery(37.544, 127.055, 1000, 15),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 0), 15, Set.of("parking"), Set.of()));
      var plan = service.poll("owner-a", id).orElseThrow().result();
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
              new PlaceQuery(37.50, 127.00, 1000, 3),
              new PlaceRecommendationPlanner.Requirements(
                  new Model.Party(2, 0), 15, Set.of("parking"), Set.of()));
      var plan = service.poll("owner-a", task).orElseThrow().result();
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
          service.start("owner-a", view, 1, new PlaceQuery(37.544, 127.055, 1000, 3), requirements);
      assertThat(service.poll("owner-a", task).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.QUEUED);
      assertThat(service.poll("owner-b", task)).isEmpty();
      queue.remove().run();
      var snapshot = service.poll("owner-a", task).orElseThrow();
      assertThat(snapshot.state()).isEqualTo(RecommendationJobRegistry.State.RESULTS);
      assertThat(snapshot.result().provider()).isEqualTo(PlaceSummary.Provider.SEOUL_OPEN_DATA);
      assertThat(snapshot.result().candidates()).hasSize(3);
      assertThat(snapshot.result().candidates())
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
