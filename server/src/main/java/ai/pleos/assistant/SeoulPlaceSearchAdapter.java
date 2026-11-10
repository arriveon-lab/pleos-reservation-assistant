package ai.pleos.assistant;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;

/** Explicit local catalog only. No Spring registration, HTTP, persistence mutation or phone IO. */
public final class SeoulPlaceSearchAdapter implements PlaceSearchAdapter {
  // Development screening window, not a provider promise of current merchant information.
  private static final Duration SNAPSHOT_MAX_AGE = Duration.ofDays(7);
  private final SeoulPlaceCatalog catalog;
  private final Clock clock;

  public SeoulPlaceSearchAdapter(SeoulPlaceCatalog catalog, Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public PlaceSearchResult search(PlaceQuery query) {
    Objects.requireNonNull(query);
    var now = clock.instant();
    var snapshot = catalog.sourceSnapshotAt();
    if (snapshot == null
        || snapshot.isAfter(now)
        || !snapshot.isAfter(now.minus(SNAPSHOT_MAX_AGE))
        || catalog.acquiredAt().isAfter(now)
        || (catalog.entries().isEmpty() && catalog.inputRows() > 0))
      return new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
    var pilot = new PlaceQuery(37.544, 127.055, 1000, 3);
    var destination =
        new PlaceSummary(
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            "query",
            "query",
            null,
            query.latitude(),
            query.longitude(),
            null,
            null,
            null,
            null);
    if (query.radiusMeters() > 1000
        || PlaceRecommendationPlanner.distance(pilot, destination) > 1000)
      return new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_QUERY);
    var listings =
        catalog.entries().stream()
            .map(SeoulPlaceCatalog.Entry::listing)
            .filter(p -> PlaceRecommendationPlanner.distance(query, p) <= query.radiusMeters())
            .sorted(
                Comparator.comparingDouble(
                        (PlaceSummary p) -> PlaceRecommendationPlanner.distance(query, p))
                    .thenComparing(PlaceSummary::providerPlaceId))
            .limit(query.limit())
            .toList();
    // Time of this local query, never the upstream observation time. Catalog provenance is
    // retained.
    return new PlaceSearchResult.Success(listings, now, 0);
  }

  public SeoulPlaceCatalog provenance() {
    return catalog;
  }
}
