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

class PublicDestinationRecommendationServiceTest {
  @Test
  void destination_outside_public_pilot_is_failure_not_empty_recommendations() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    var catalog =
        SeoulPlaceCatalog.load(
            Path.of("src/test/resources/places/seoul-catalog-fictional.json"),
            new ObjectMapper().findAndRegisterModules());
    try (var service =
        new PublicDestinationRecommendationService(
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
        new PublicDestinationRecommendationService(
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
