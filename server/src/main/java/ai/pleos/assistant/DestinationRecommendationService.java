package ai.pleos.assistant;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Destination-only transient shortlist. No GPT, telephone, booking, or persistence. */
public final class DestinationRecommendationService implements AutoCloseable {
  public record SourceInfo(
      PlaceSummary.Provider provider,
      String datasetId,
      String catalogUrl,
      String licenseUrl,
      String attribution,
      java.time.Instant acquiredAt,
      java.time.Instant sourceSnapshotAt) {}

  private final PlaceSearchAdapter places;
  private final PlaceSummary.Provider provider;
  private final SourceInfo source;
  private final PlaceRecommendationPlanner planner;
  private final RecommendationJobRegistry<PlaceRecommendationPlanner.Plan> jobs;

  public DestinationRecommendationService(
      SeoulPlaceSearchAdapter places, Clock clock, Executor executor) {
    this(
        places,
        PlaceSummary.Provider.SEOUL_OPEN_DATA,
        new SourceInfo(
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            places.provenance().datasetId(),
            places.provenance().catalogUrl(),
            places.provenance().licenseUrl(),
            places.provenance().attribution(),
            places.provenance().acquiredAt(),
            places.provenance().sourceSnapshotAt()),
        clock,
        executor);
  }

  public DestinationRecommendationService(
      KakaoPlaceSearchAdapter places, Clock clock, Executor executor) {
    this(
        places,
        PlaceSummary.Provider.KAKAO,
        new SourceInfo(PlaceSummary.Provider.KAKAO, null, null, null, "Kakao Local", null, null),
        clock,
        executor);
  }

  DestinationRecommendationService(
      PlaceSearchAdapter places,
      PlaceSummary.Provider provider,
      SourceInfo source,
      Clock clock,
      Executor executor) {
    this.places = Objects.requireNonNull(places);
    this.provider = Objects.requireNonNull(provider);
    this.source = Objects.requireNonNull(source);
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
          var plan = planner.evaluate(provider, destinationWindow, listing, requirements);
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

  public SourceInfo source() {
    return source;
  }

  @Override
  public void close() {
    jobs.close();
    if (places instanceof KakaoPlaceSearchAdapter kakao) kakao.close();
  }
}
