package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import ai.pleos.assistant.ux.DrivingRestrictionController.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrivingRestrictionControllerTest {
  static class SourceStub implements Source {
    final List<Observer> listeners = new ArrayList<>();
    int closes;
    boolean deny, brokenClose;

    public void open(Observer observer) {
      if (deny) throw new SecurityException();
      listeners.add(observer);
    }

    public void close() {
      closes++;
      if (brokenClose) throw new IllegalStateException();
    }

    Observer latest() {
      return listeners.get(listeners.size() - 1);
    }
  }

  record Harness(SourceStub source, DrivingRestrictionController controller, List<State> events) {}

  Harness create() {
    var source = new SourceStub();
    var events = new ArrayList<State>();
    return new Harness(source, new DrivingRestrictionController(source, events::add), events);
  }

  @Test
  void initialAndConnectingStatesCannotPermitInput() {
    var h = create();
    assertFalse(h.controller.state().restriction().allowsInput());
    h.controller.start();
    assertEquals(Reason.CONNECTING, h.controller.state().reason());
    assertFalse(h.controller.state().restriction().allowsInput());
  }

  @Test
  void onlyExplicitPlatformBaselinePermitsInput() {
    var h = create();
    h.controller.start();
    h.source.latest().reading(false, 0);
    assertEquals(
        MockRepository.Restriction.PLATFORM_UNRESTRICTED, h.controller.state().restriction());
    assertEquals(Reason.PLATFORM, h.controller.state().reason());
  }

  @Test
  void restrictionFlagsAndDoRequirementAreConservative() {
    for (int mask : new int[] {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 0x50, 0x1ff}) {
      var h = create();
      h.controller.start();
      h.source.latest().reading(false, mask);
      assertEquals(MockRepository.Restriction.RESTRICTED, h.controller.state().restriction());
    }
    var h = create();
    h.controller.start();
    h.source.latest().reading(true, 0);
    assertEquals(MockRepository.Restriction.RESTRICTED, h.controller.state().restriction());
  }

  @Test
  void invalidReadingFailsClosedAndDetaches() {
    var h = create();
    h.controller.start();
    h.source.latest().reading(false, -1);
    assertEquals(Reason.READ_FAILED, h.controller.state().reason());
    assertFalse(h.controller.state().restriction().allowsInput());
    assertEquals(2, h.source.closes);
  }

  @Test
  void allAvailabilityFailuresBlockAndIgnoreLateBaseline() {
    for (Reason reason :
        List.of(
            Reason.UNSUPPORTED,
            Reason.MISSING_LIBRARY,
            Reason.PERMISSION_DENIED,
            Reason.SERVICE_DISCONNECTED,
            Reason.READ_FAILED,
            Reason.CONNECT_TIMEOUT)) {
      var h = create();
      h.controller.start();
      Observer old = h.source.latest();
      old.reading(false, 0);
      old.failure(reason);
      old.reading(false, 0);
      assertEquals(reason, h.controller.state().reason());
      assertFalse(h.controller.state().restriction().allowsInput());
    }
  }

  @Test
  void thrownPermissionDenialIsVisible() {
    var h = create();
    h.source.deny = true;
    h.controller.start();
    assertEquals(Reason.PERMISSION_DENIED, h.controller.state().reason());
  }

  @Test
  void stopInvalidatesBeforeUnregisterAndLateCallback() {
    var h = create();
    h.controller.start();
    Observer old = h.source.latest();
    old.reading(false, 0);
    h.controller.stop();
    old.reading(false, 0);
    assertEquals(Reason.STOPPED, h.controller.state().reason());
    assertFalse(h.controller.state().restriction().allowsInput());
  }

  @Test
  void reopeningWaitsForNewReadAndRejectsOldGeneration() {
    var h = create();
    h.controller.start();
    Observer old = h.source.latest();
    old.reading(false, 0);
    h.controller.stop();
    h.controller.start();
    old.reading(false, 0);
    assertEquals(Reason.CONNECTING, h.controller.state().reason());
    h.source.latest().reading(false, 0);
    assertTrue(h.controller.state().restriction().allowsInput());
    old.failure(Reason.READ_FAILED);
    assertTrue(h.controller.state().restriction().allowsInput());
  }

  @Test
  void restrictionChangeRemovesPermissionImmediately() {
    var h = create();
    h.controller.start();
    h.source.latest().reading(false, 0);
    h.source.latest().reading(true, 0x50);
    assertFalse(h.controller.state().restriction().allowsInput());
    h.source.latest().reading(false, 0);
    assertTrue(h.controller.state().restriction().allowsInput());
  }

  @Test
  void debugFailureCannotUnlockWithAnOldCallback() {
    var h = create();
    h.controller.start();
    Observer old = h.source.latest();
    old.reading(false, 0);
    h.controller.fail(Reason.DEBUG_FAILURE);
    old.reading(false, 0);
    assertEquals(Reason.DEBUG_FAILURE, h.controller.state().reason());
    assertFalse(h.controller.state().restriction().allowsInput());
  }

  @Test
  void debugRestrictionOnlyTightensAndFreshPlatformReadIsRequired() {
    var h = create();
    h.controller.start();
    Observer old = h.source.latest();
    old.reading(false, 0);
    h.controller.addDebugRestriction();
    old.reading(false, 0);
    assertEquals(Reason.DEBUG_RESTRICTED, h.controller.state().reason());
    assertFalse(h.controller.state().restriction().allowsInput());
    h.controller.start();
    assertFalse(h.controller.state().restriction().allowsInput());
    h.source.latest().reading(false, 0);
    assertTrue(h.controller.state().restriction().allowsInput());
  }

  @Test
  void cleanupFailureCannotOpenAnotherConnectionOrUnlock() {
    var h = create();
    h.controller.start();
    Observer old = h.source.latest();
    old.reading(false, 0);
    h.source.brokenClose = true;
    h.controller.start();
    old.reading(false, 0);
    assertEquals(Reason.CLEANUP_FAILED, h.controller.state().reason());
    assertEquals(1, h.source.listeners.size());
  }

  @Test
  void foregroundRestartDoesNotRetainPermissionOrDuplicateStableEvents() {
    var h = create();
    h.controller.start();
    h.source.latest().reading(false, 0);
    int count = h.events.size();
    h.source.latest().reading(false, 0);
    assertEquals(count, h.events.size());
    h.controller.start();
    assertFalse(h.controller.state().restriction().allowsInput());
    assertEquals(2, h.source.listeners.size());
  }
}
