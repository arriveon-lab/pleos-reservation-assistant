package ai.pleos.assistant.probe;

/** Main-thread-owned diagnostic state. SDK responses have no request/destination correlation ID. */
final class ProbeState<T> {
  private boolean closed;
  private boolean connected;
  private boolean awaitingDestination;
  private T destination;
  private long routeRevision;

  boolean connect() {
    if (closed) return false;
    connected = true;
    return true;
  }

  boolean requestDestination() {
    if (!connected || closed) return false;
    destination = null;
    awaitingDestination = true;
    return true;
  }

  boolean receiveDestination(T value) {
    if (closed || !connected || !awaitingDestination) return false;
    awaitingDestination = false;
    destination = value;
    return true;
  }

  void invalidate() {
    routeRevision++;
    destination = null;
    awaitingDestination = false;
  }

  void disconnect() {
    connected = false;
    invalidate();
  }

  void close() {
    closed = true;
    disconnect();
  }

  boolean isClosed() {
    return closed;
  }

  T destination() {
    return destination;
  }

  long routeRevision() {
    return routeRevision;
  }

  static boolean validCoordinates(double latitude, double longitude) {
    return Double.isFinite(latitude)
        && Double.isFinite(longitude)
        && latitude >= -90
        && latitude <= 90
        && longitude >= -180
        && longitude <= 180;
  }
}
