package ai.pleos.assistant;

/**
 * Coordinates are the destination, not the vehicle's current position. Units are degrees/metres.
 */
public record PlaceQuery(double latitude, double longitude, int radiusMeters, int limit) {
  public PlaceQuery {
    if (!Double.isFinite(latitude)
        || latitude < -90
        || latitude > 90
        || !Double.isFinite(longitude)
        || longitude < -180
        || longitude > 180
        || radiusMeters < 1
        || radiusMeters > 20_000
        || limit < 1
        || limit > 15) {
      throw new IllegalArgumentException("Invalid restaurant search bounds");
    }
  }

  public static PlaceQuery forDestination(Model.Destination destination) {
    // Local development defaults; widening the pilot or issuing more requests needs a new decision.
    return new PlaceQuery(destination.latitude(), destination.longitude(), 1_000, 15);
  }
}
