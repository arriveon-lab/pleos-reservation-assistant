package ai.pleos.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Internal, single-process prototype. Owner scopes must come from a trusted caller, not JSON. */
final class PlacePreviewRegistry implements AutoCloseable {
  record Owner(String value) {
    Owner {
      if (value == null || !value.matches("[A-Za-z0-9._:-]{1,100}"))
        throw new IllegalArgumentException("Invalid preview owner scope");
    }
  }

  record Limits(
      int sessions,
      int sessionsPerOwner,
      int concurrentCalls,
      int dispatches,
      Duration idleTimeout,
      Duration requestTimeout) {
    Limits {
      if (sessions < 1
          || sessions > 64
          || sessionsPerOwner < 1
          || sessionsPerOwner > sessions
          || concurrentCalls < 1
          || concurrentCalls > sessions
          || dispatches < 1
          || dispatches > 1000) throw new IllegalArgumentException("Invalid preview capacity");
      requireDuration(idleTimeout, Duration.ofHours(1));
      requireDuration(requestTimeout, Duration.ofSeconds(3));
    }
  }

  enum Rejection {
    NOT_FOUND,
    SESSION_LIMIT,
    CLOSED
  }

  static final class AccessRejected extends RuntimeException {
    private final Rejection reason;

    AccessRejected(Rejection reason) {
      super("Preview access rejected: " + reason);
      this.reason = reason;
    }

    Rejection reason() {
      return reason;
    }
  }

  record Statistics(int sessions, int runningCalls, int dispatches) {}

  private final Object lock = new Object();
  private final PlaceSearchAdapter adapter;
  private final Limits limits;
  private final LongSupplier ticker;
  private final Semaphore permits;
  private final Map<UUID, Entry> entries = new HashMap<>();
  private int dispatched;
  private boolean closed;

  PlacePreviewRegistry(PlaceSearchAdapter adapter, Limits limits) {
    this(adapter, limits, System::nanoTime);
  }

  // Monotonic test time; wall-clock corrections must not extend an idle lease.
  PlacePreviewRegistry(PlaceSearchAdapter adapter, Limits limits, LongSupplier ticker) {
    this.adapter = Objects.requireNonNull(adapter);
    this.limits = Objects.requireNonNull(limits);
    this.ticker = Objects.requireNonNull(ticker);
    permits = new Semaphore(limits.concurrentCalls());
  }

  UUID open(Owner owner) {
    Objects.requireNonNull(owner);
    return withEntries(
        now -> {
          long ownerSessions = entries.values().stream().filter(e -> e.owner.equals(owner)).count();
          if (entries.size() >= limits.sessions() || ownerSessions >= limits.sessionsPerOwner())
            throw new AccessRejected(Rejection.SESSION_LIMIT);
          UUID id;
          do {
            id = UUID.randomUUID();
          } while (entries.containsKey(id));
          Entry entry = new Entry(owner, now);
          entry.session =
              new PlacePreviewSession(query -> dispatch(entry, query), limits.requestTimeout());
          entries.put(id, entry);
          return id;
        });
  }

  CompletionStage<PlacePreviewSession.Outcome> start(
      Owner owner, UUID id, long generation, PlaceQuery query) {
    return start(owner, id, generation, query, null);
  }

  CompletionStage<PlacePreviewSession.Outcome> start(
      Owner owner,
      UUID id,
      long generation,
      PlaceQuery query,
      PlaceRecommendationPlanner.Requirements requirements) {
    PlacePreviewSession.requireOutsideTransaction();
    if (generation < 1) throw new IllegalArgumentException("Invalid preview generation");
    Objects.requireNonNull(query);
    return lookup(owner, id).session.start(generation, query, requirements);
  }

  boolean cancel(Owner owner, UUID id, long generation) {
    if (generation < 1) throw new IllegalArgumentException("Invalid preview generation");
    return lookup(owner, id).session.cancel(generation);
  }

  void close(Owner owner, UUID id) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(id);
    Entry entry =
        withEntries(
            now -> {
              Entry found = entries.get(id);
              requireOwner(found, owner);
              entries.remove(id);
              found.active = false;
              return found;
            });
    entry.session.close();
  }

  private Entry lookup(Owner owner, UUID id) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(id);
    return withEntries(
        now -> {
          Entry entry = entries.get(id);
          requireOwner(entry, owner);
          entry.touchedAt = now;
          return entry;
        });
  }

  private static void requireOwner(Entry entry, Owner owner) {
    // Unknown, expired and foreign IDs are indistinguishable to the caller.
    if (entry == null || !entry.owner.equals(owner)) throw new AccessRejected(Rejection.NOT_FOUND);
  }

  private PlaceSearchResult dispatch(Entry entry, PlaceQuery query) {
    if (!permits.tryAcquire()) return failure(PlaceSearchFailure.RATE_LIMITED);
    try {
      synchronized (lock) {
        if (closed || !entry.active || Thread.currentThread().isInterrupted())
          return failure(PlaceSearchFailure.CANCELLED);
        if (dispatched >= limits.dispatches()) return failure(PlaceSearchFailure.RATE_LIMITED);
        dispatched++; // Conservative admission count; failures/cancellation never refund budget.
      }
      return adapter.search(query);
    } finally {
      // Cancellation/expiry must not return a permit before an uncooperative call actually exits.
      permits.release();
    }
  }

  int reapExpired() {
    List<Entry> expired = new ArrayList<>();
    synchronized (lock) {
      removeExpired(ticker.getAsLong(), expired);
    }
    expired.forEach(entry -> entry.session.close());
    return expired.size();
  }

  private void removeExpired(long now, List<Entry> expired) {
    entries
        .values()
        .removeIf(
            entry -> {
              if (now - entry.touchedAt < limits.idleTimeout().toNanos()) return false;
              entry.active = false;
              expired.add(entry);
              return true;
            });
  }

  private <T> T withEntries(Function<Long, T> action) {
    List<Entry> expired = new ArrayList<>();
    try {
      synchronized (lock) {
        requireOpen();
        long now = ticker.getAsLong();
        removeExpired(now, expired);
        return action.apply(now);
      }
    } finally {
      // Closing can complete user continuations. It must happen even on rejection, outside lock.
      expired.forEach(entry -> entry.session.close());
    }
  }

  Statistics statistics() {
    synchronized (lock) {
      return new Statistics(
          entries.size(), limits.concurrentCalls() - permits.availablePermits(), dispatched);
    }
  }

  @Override
  public void close() {
    List<Entry> pending;
    synchronized (lock) {
      if (closed) return;
      closed = true;
      pending = List.copyOf(entries.values());
      pending.forEach(entry -> entry.active = false);
      entries.clear();
    }
    pending.forEach(entry -> entry.session.close());
  }

  private void requireOpen() {
    if (closed) throw new AccessRejected(Rejection.CLOSED);
  }

  private static void requireDuration(Duration value, Duration maximum) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0)
      throw new IllegalArgumentException("Invalid preview duration");
  }

  private static PlaceSearchResult.Failure failure(PlaceSearchFailure kind) {
    return new PlaceSearchResult.Failure(kind);
  }

  private static final class Entry {
    final Owner owner;
    long touchedAt;
    boolean active = true; // Guarded by the registry lock, including dispatch admission.
    PlacePreviewSession session;

    Entry(Owner owner, long touchedAt) {
      this.owner = owner;
      this.touchedAt = touchedAt;
    }
  }
}
