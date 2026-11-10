package ai.pleos.assistant;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Destination-only local catalog shortlist. No Kakao content, GPT, telephone, arrival fact,
 * reservation authority, or persistence enters this service.
 */
public final class PublicDestinationRecommendationService implements AutoCloseable {
  private final SeoulPlaceSearchAdapter places;
  private final PlaceRecommendationPlanner planner;
  private final RecommendationJobRegistry<PlaceRecommendationPlanner.Plan> jobs;

  public PublicDestinationRecommendationService(
      SeoulPlaceSearchAdapter places, Clock clock, Executor executor) {
    this.places = Objects.requireNonNull(places);
    planner = new PlaceRecommendationPlanner(Objects.requireNonNull(clock));
    jobs = new RecommendationJobRegistry<>(clock, executor);
  }

  public UUID start(
      String owner,
      UUID viewId,
      long generation,
      PlaceQuery destinationWindow,
      PlaceRecommendationPlanner.Requirements requirements) {
    Objects.requireNonNull(destinationWindow);
    Objects.requireNonNull(requirements);
    return jobs.submit(
        owner,
        viewId,
        generation,
        current -> {
          if (!current.getAsBoolean()) return null;
          PlaceSearchResult listing = places.search(destinationWindow);
          if (!current.getAsBoolean()) return null;
          var plan =
              planner.evaluate(
                  PlaceSummary.Provider.SEOUL_OPEN_DATA, destinationWindow, listing, requirements);
          return current.getAsBoolean() ? plan : null;
        });
  }

  public Optional<RecommendationJobRegistry.Snapshot<PlaceRecommendationPlanner.Plan>> poll(
      String owner, UUID taskId) {
    return jobs.poll(owner, taskId);
  }

  public void invalidate(String owner, UUID viewId) {
    jobs.invalidate(owner, viewId);
  }

  public SeoulPlaceCatalog provenance() {
    return places.provenance();
  }

  @Override
  public void close() {
    jobs.close();
  }
}
