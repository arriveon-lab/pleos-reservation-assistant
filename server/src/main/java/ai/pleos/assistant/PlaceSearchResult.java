package ai.pleos.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public sealed interface PlaceSearchResult {
  /** Empty is a successful search. Rejected records are not counted as genuine empty results. */
  record Success(List<PlaceSummary> places, Instant fetchedAt, int rejectedRecords)
      implements PlaceSearchResult {
    public Success {
      places = List.copyOf(places);
      Objects.requireNonNull(fetchedAt);
      if (rejectedRecords < 0) throw new IllegalArgumentException("Negative rejected count");
    }
  }

  record Failure(PlaceSearchFailure kind) implements PlaceSearchResult {
    public Failure {
      Objects.requireNonNull(kind);
    }
  }
}
