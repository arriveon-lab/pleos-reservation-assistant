package ai.pleos.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Determines whether to ask the user about a delay; this never authorizes a phone call. */
public final class DelayContactPolicy {
  private static final Duration CHECK_INTERVAL = Duration.ofMinutes(10);
  private static final Duration DELAY_THRESHOLD = Duration.ofMinutes(10);

  public enum Decision {
    NOT_DUE,
    ETA_UNVERIFIED,
    ON_TIME,
    ALREADY_ASKED,
    ASK_USER
  }

  public record Observation(
      Instant checkedAt,
      Instant lastCheckedAt,
      Instant confirmedReservationAt,
      Instant predictedArrivalAt,
      boolean etaVerified,
      Instant lastAskedArrivalAt) {
    public Observation {
      Objects.requireNonNull(checkedAt);
      Objects.requireNonNull(confirmedReservationAt);
      if (lastCheckedAt != null && lastCheckedAt.isAfter(checkedAt))
        throw new IllegalArgumentException("Last check is in the future");
    }
  }

  public Decision evaluate(Observation observation) {
    if (observation.lastCheckedAt() != null
        && observation.checkedAt().isBefore(observation.lastCheckedAt().plus(CHECK_INTERVAL)))
      return Decision.NOT_DUE;
    if (!observation.etaVerified() || observation.predictedArrivalAt() == null)
      return Decision.ETA_UNVERIFIED;
    if (observation
        .predictedArrivalAt()
        .isBefore(observation.confirmedReservationAt().plus(DELAY_THRESHOLD)))
      return Decision.ON_TIME;
    if (observation.lastAskedArrivalAt() != null
        && observation
            .predictedArrivalAt()
            .isBefore(observation.lastAskedArrivalAt().plus(DELAY_THRESHOLD)))
      return Decision.ALREADY_ASKED;
    return Decision.ASK_USER;
  }
}
