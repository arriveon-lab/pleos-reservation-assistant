package ai.pleos.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DelayContactPolicyTest {
  private static final Instant CHECK = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant BOOKING = Instant.parse("2026-11-10T10:30:00Z");
  private final DelayContactPolicy policy = new DelayContactPolicy();

  @Test
  void asksAtExactTenMinuteDelayButNeverDispatches() {
    assertEquals(
        DelayContactPolicy.Decision.ASK_USER,
        policy.evaluate(observation(BOOKING.plusSeconds(600), true, null, null)));
    assertEquals(
        DelayContactPolicy.Decision.ON_TIME,
        policy.evaluate(observation(BOOKING.plusSeconds(599), true, null, null)));
  }

  @Test
  void doesNotAskAgainDuringSameDelayOrBeforeNextCheck() {
    Instant arrival = BOOKING.plusSeconds(600);
    assertEquals(
        DelayContactPolicy.Decision.ALREADY_ASKED,
        policy.evaluate(observation(arrival.plusSeconds(60), true, null, arrival)));
    assertEquals(
        DelayContactPolicy.Decision.NOT_DUE,
        policy.evaluate(observation(arrival.plusSeconds(60), true, CHECK.minusSeconds(599), null)));
  }

  @Test
  void unverifiedEtaCannotTriggerContact() {
    assertEquals(
        DelayContactPolicy.Decision.ETA_UNVERIFIED,
        policy.evaluate(observation(BOOKING.plusSeconds(1200), false, null, null)));
  }

  private static DelayContactPolicy.Observation observation(
      Instant arrival, boolean verified, Instant lastCheck, Instant lastAskedArrival) {
    return new DelayContactPolicy.Observation(
        CHECK, lastCheck, BOOKING, arrival, verified, lastAskedArrival);
  }
}
