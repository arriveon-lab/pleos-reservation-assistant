package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DestinationRecommendationStateTest {
  private static DestinationRecommendationState.Destination destination(String name) {
    return new DestinationRecommendationState.Destination(
        37.544, 127.055, name, "fictional-poi", Instant.parse("2026-11-10T00:00:00Z"));
  }

  @Test
  void routeChangeAndCancellationRejectLateResults() {
    var state = new DestinationRecommendationState();
    assertNull(state.beginRecommendation());
    state.observeDestination(destination("가상 목적지 A"));
    var a = state.beginRecommendation();
    assertNotNull(a);
    assertTrue(state.isCurrent(a));

    state.invalidateRoute();
    assertNull(state.destination());
    assertFalse(state.isCurrent(a));
    assertNull(state.beginRecommendation());

    state.observeDestination(destination("가상 목적지 B"));
    var b = state.beginRecommendation();
    assertTrue(state.isCurrent(b));
    assertFalse(state.isCurrent(a));
    state.close();
    assertFalse(state.isCurrent(b));
    assertNull(state.beginRecommendation());
  }

  @Test
  void newObservationAndNewRequestSupersedeOldWork() {
    var state = new DestinationRecommendationState();
    state.observeDestination(destination("가상 목적지"));
    var first = state.beginRecommendation();
    var second = state.beginRecommendation();
    assertFalse(state.isCurrent(first));
    assertTrue(state.isCurrent(second));

    state.observeDestination(destination("가상 목적지"));
    assertFalse(state.isCurrent(second));
    assertTrue(state.isCurrent(state.beginRecommendation()));
  }
}
