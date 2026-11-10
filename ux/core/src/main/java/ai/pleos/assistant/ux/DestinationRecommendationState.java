package ai.pleos.assistant.ux;

import java.time.Instant;
import java.util.Objects;

/**
 * SDK-independent boundary for a destination-based recommendation. The vehicle adapter owns SDK
 * callbacks; this state only prevents an older recommendation from becoming current after a route
 * change, a fresh destination observation, or a newer request. It cannot correlate an SDK callback
 * with its original SDK request when the SDK supplies no correlation identifier.
 */
public final class DestinationRecommendationState {
  public record Destination(
      double latitude, double longitude, String name, String poiId, Instant observedAt) {
    public Destination {
      if (!Double.isFinite(latitude)
          || !Double.isFinite(longitude)
          || latitude < -90
          || latitude > 90
          || longitude < -180
          || longitude > 180
          || name == null
          || name.isBlank()
          || poiId == null
          || observedAt == null)
        throw new IllegalArgumentException("Invalid destination observation");
    }

    @Override
    public String toString() {
      return "Destination[private vehicle data omitted]";
    }
  }

  public record Ticket(long routeGeneration, long requestGeneration, Destination destination) {
    @Override
    public String toString() {
      return "RecommendationTicket[private vehicle data omitted]";
    }
  }

  private long routeGeneration;
  private long requestGeneration;
  private Destination destination;
  private Ticket active;
  private boolean closed;

  /** Called for route change, cancellation, arrival, disconnection, or a new destination query. */
  public synchronized void invalidateRoute() {
    if (closed) return;
    routeGeneration++;
    destination = null;
    active = null;
  }

  /** A validated observation; no ETA or current-route association is inferred here. */
  public synchronized void observeDestination(Destination value) {
    if (closed) return;
    Objects.requireNonNull(value);
    routeGeneration++;
    destination = value;
    active = null;
  }

  public synchronized Destination destination() {
    return destination;
  }

  /** Returns null while no destination is usable. */
  public synchronized Ticket beginRecommendation() {
    if (closed || destination == null) return null;
    active = new Ticket(routeGeneration, ++requestGeneration, destination);
    return active;
  }

  /** Check immediately before applying a completed recommendation to UI state. */
  public synchronized boolean isCurrent(Ticket ticket) {
    return !closed
        && ticket != null
        && active == ticket
        && ticket.routeGeneration() == routeGeneration
        && ticket.requestGeneration() == requestGeneration;
  }

  public synchronized void close() {
    closed = true;
    destination = null;
    active = null;
  }
}
