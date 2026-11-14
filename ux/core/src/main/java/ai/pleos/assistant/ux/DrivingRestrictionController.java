package ai.pleos.assistant.ux;

import java.util.Objects;
import java.util.function.Consumer;

/** Each foreground connection owns a generation. Failure never becomes permission to interact. */
public final class DrivingRestrictionController {
  public enum Reason {
    CONNECTING,
    PLATFORM,
    UNSUPPORTED,
    MISSING_LIBRARY,
    SERVICE_DISCONNECTED,
    PERMISSION_DENIED,
    READ_FAILED,
    CONNECT_TIMEOUT,
    STOPPED,
    CLEANUP_FAILED,
    DEBUG_FAILURE,
    DEBUG_RESTRICTED
  }

  public record State(
      MockRepository.Restriction restriction, Reason reason, boolean requiresDo, int flags) {}

  public interface Observer {
    void reading(boolean requiresDo, int flags);

    void failure(Reason reason);
  }

  public interface Source {
    void open(Observer observer) throws Exception;

    void close() throws Exception;
  }

  private final Source source;
  private final Consumer<State> changed;
  private long generation;
  private boolean active;
  private State state = unknown(Reason.STOPPED);

  public DrivingRestrictionController(Source source, Consumer<State> changed) {
    this.source = source;
    this.changed = changed;
  }

  public synchronized State state() {
    return state;
  }

  public synchronized void start() {
    active = false;
    long ticket = ++generation;
    publish(unknown(Reason.CONNECTING));
    if (!cleanup()) return;
    active = true;
    try {
      source.open(
          new Observer() {
            public void reading(boolean requiresDo, int flags) {
              synchronized (DrivingRestrictionController.this) {
                if (!active || generation != ticket) return;
                if (flags < 0) {
                  fail(Reason.READ_FAILED);
                  return;
                }
                publish(
                    new State(
                        !requiresDo && flags == 0
                            ? MockRepository.Restriction.PLATFORM_UNRESTRICTED
                            : MockRepository.Restriction.RESTRICTED,
                        Reason.PLATFORM,
                        requiresDo,
                        flags));
              }
            }

            public void failure(Reason reason) {
              synchronized (DrivingRestrictionController.this) {
                if (active && generation == ticket) fail(reason);
              }
            }
          });
    } catch (SecurityException denied) {
      fail(Reason.PERMISSION_DENIED);
    } catch (Exception | LinkageError unavailable) {
      fail(Reason.READ_FAILED);
    }
  }

  public synchronized void stop() {
    fail(Reason.STOPPED);
  }

  public synchronized void fail(Reason reason) {
    active = false;
    ++generation;
    publish(unknown(reason));
    cleanup();
  }

  /** A developer can only add restrictions. Unlock always requires a new platform read. */
  public synchronized void addDebugRestriction() {
    active = false;
    ++generation;
    publish(new State(MockRepository.Restriction.RESTRICTED, Reason.DEBUG_RESTRICTED, true, -1));
    cleanup();
  }

  private boolean cleanup() {
    try {
      source.close();
      return true;
    } catch (Exception | LinkageError failure) {
      publish(unknown(Reason.CLEANUP_FAILED));
      return false;
    }
  }

  private static State unknown(Reason reason) {
    return new State(MockRepository.Restriction.UNKNOWN, reason, false, -1);
  }

  private void publish(State next) {
    if (!Objects.equals(state, next)) {
      state = next;
      changed.accept(next);
    }
  }
}
