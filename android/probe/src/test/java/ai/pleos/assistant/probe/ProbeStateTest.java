package ai.pleos.assistant.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProbeStateTest {
  @Test
  public void destinationRequiresConnectionAndExplicitQuery() {
    ProbeState<String> state = new ProbeState<>();
    assertFalse(state.requestDestination());
    assertFalse(state.receiveDestination("synthetic"));
    assertTrue(state.connect());
    assertFalse(state.receiveDestination("unsolicited"));
    assertTrue(state.requestDestination());
    assertTrue(state.receiveDestination("synthetic"));
    assertEquals("synthetic", state.destination());
    assertFalse(state.receiveDestination("duplicate"));
    assertEquals("synthetic", state.destination());
  }

  @Test
  public void cancellationOrArrivalInvalidatesPendingAndExistingDestination() {
    ProbeState<String> state = connected();
    state.requestDestination();
    state.receiveDestination("synthetic");
    state.invalidate();
    assertNull(state.destination());
    assertFalse(state.receiveDestination("late"));
    state.requestDestination();
    state.invalidate();
    assertFalse(state.receiveDestination("late-query"));
    assertEquals(2, state.routeRevision());
  }

  @Test
  public void refreshClearsOldDestinationBeforeResponse() {
    ProbeState<String> state = connected();
    state.requestDestination();
    state.receiveDestination("first");
    state.requestDestination();
    assertNull(state.destination());
    assertTrue(state.receiveDestination("unverified-response"));
  }

  @Test
  public void disconnectBlocksLateResponseAndNewQueries() {
    ProbeState<String> state = connected();
    state.requestDestination();
    state.disconnect();
    assertFalse(state.receiveDestination("late"));
    assertFalse(state.requestDestination());
    assertNull(state.destination());
  }

  @Test
  public void reconnectRequiresNewDestinationQuery() {
    ProbeState<String> state = connected();
    assertTrue(state.requestDestination());
    assertTrue(state.receiveDestination("before-disconnect"));

    state.disconnect();
    assertNull(state.destination());
    assertTrue(state.connect());
    assertFalse(state.receiveDestination("late-before-new-query"));

    assertTrue(state.requestDestination());
    assertTrue(state.receiveDestination("after-reconnect"));
    assertEquals("after-reconnect", state.destination());
  }

  @Test
  public void closeCannotBeReopenedByLateInitializationOrDestination() {
    ProbeState<String> state = connected();
    state.requestDestination();
    state.close();
    assertTrue(state.isClosed());
    assertFalse(state.connect());
    assertFalse(state.requestDestination());
    assertFalse(state.receiveDestination("late"));
    assertNull(state.destination());
  }

  @Test
  public void coordinatesRejectNonFiniteAndOutOfRangeWithoutRejectingZero() {
    assertTrue(ProbeState.validCoordinates(0, 0));
    assertTrue(ProbeState.validCoordinates(-90, 180));
    assertTrue(ProbeState.validCoordinates(90, -180));
    assertFalse(ProbeState.validCoordinates(Double.NaN, 0));
    assertFalse(ProbeState.validCoordinates(0, Double.POSITIVE_INFINITY));
    assertFalse(ProbeState.validCoordinates(-90.01, 0));
    assertFalse(ProbeState.validCoordinates(0, 180.01));
  }

  private static ProbeState<String> connected() {
    ProbeState<String> state = new ProbeState<>();
    state.connect();
    return state;
  }
}
