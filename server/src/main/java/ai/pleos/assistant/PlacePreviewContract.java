package ai.pleos.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Experimental MOCK wire types, separate from persisted searches and reservation facts. */
public final class PlacePreviewContract {
  private PlacePreviewContract() {}

  public record CreateRequest() {}

  public record Query(
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
      @NotNull @Min(1) @Max(20_000) Integer radiusMeters,
      @NotNull @Min(1) @Max(3) Integer limit) {
    PlaceQuery internal() {
      return new PlaceQuery(latitude, longitude, radiusMeters, limit);
    }
  }

  public record QueryRequest(
      @NotNull @Min(1) @Max(9_007_199_254_740_991L) Long generation, @NotNull @Valid Query query) {}

  public record CancelRequest(@NotNull @Min(1) @Max(9_007_199_254_740_991L) Long generation) {}

  public record Session(UUID previewId, String mode) {}

  public record Closed(UUID previewId, String state, String mode) {}

  public record Cancelled(
      UUID previewId, long generation, boolean cancelledWork, String state, String mode) {}

  // Display-only values: no contact, provider ID, coordinates, facts or booking reference.
  public record Place(
      String name,
      String cuisine,
      String address,
      Integer distanceMeters,
      String parking,
      String childFriendly,
      String reservationAvailability,
      boolean bookable) {}

  public record Result(
      UUID previewId,
      long generation,
      String state,
      String placeSource,
      String reservationMode,
      List<Place> places,
      Instant fetchedAt,
      Instant expiresAt,
      String failure,
      int rejectedRecords,
      String mode) {}
}
