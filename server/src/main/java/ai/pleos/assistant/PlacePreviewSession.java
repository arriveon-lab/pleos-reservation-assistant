package ai.pleos.assistant;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal, explicitly owned single-screen prototype; not a Spring bean or HTTP API. Only synthetic
 * adapters are used until #11/#12 settle data use, ownership and the public preview contract.
 */
final class PlacePreviewSession implements AutoCloseable {
  enum Status {
    RETURNED,
    SUPERSEDED,
    CANCELLED,
    TIMED_OUT,
    STALE_GENERATION,
    REQUEST_CONFLICT,
    ALREADY_FINISHED,
    CLOSED
  }

  record Outcome(long generation, Status status, PlaceSearchResult result) {
    Outcome {
      if (generation < 1 || status == null || (status == Status.RETURNED) != (result != null))
        throw new IllegalArgumentException("Invalid preview outcome");
    }
  }

  private final Object lock = new Object();
  private final PlaceSearchAdapter adapter;
  private final Duration timeout;
  private final ThreadPoolExecutor worker;
  private final ScheduledThreadPoolExecutor deadlines;
  private long lastGeneration;
  private PlaceQuery lastQuery;
  private PlaceRecommendationPlanner.Requirements lastRequirements;
  private Flight current;
  private boolean closed;

  PlacePreviewSession(PlaceSearchAdapter adapter, Duration timeout) {
    this.adapter = Objects.requireNonNull(adapter);
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(3)) > 0)
      throw new IllegalArgumentException("Invalid preview timeout");
    this.timeout = timeout;
    worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("place-preview-worker-", 0).factory());
    deadlines =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("place-preview-deadline-", 0).factory());
    deadlines.setRemoveOnCancelPolicy(true);
  }

  CompletionStage<Outcome> start(long generation, PlaceQuery query) {
    return start(generation, query, null);
  }

  CompletionStage<Outcome> start(
      long generation, PlaceQuery query, PlaceRecommendationPlanner.Requirements requirements) {
    requireGeneration(generation);
    Objects.requireNonNull(query);
    requireOutsideTransaction();
    Flight previous;
    Flight next;
    synchronized (lock) {
      if (closed) return completed(generation, Status.CLOSED);
      if (generation < lastGeneration) return completed(generation, Status.STALE_GENERATION);
      if (generation == lastGeneration) {
        if (lastQuery != null
            && (!lastQuery.equals(query) || !Objects.equals(lastRequirements, requirements)))
          return completed(generation, Status.REQUEST_CONFLICT);
        // A completed place response is deliberately not cached or automatically re-requested.
        return current == null
            ? completed(generation, Status.ALREADY_FINISHED)
            : current.answer.minimalCompletionStage();
      }
      previous = current;
      if (previous != null) stop(previous);
      lastGeneration = generation;
      lastQuery = query;
      lastRequirements = requirements;
      next = new Flight(generation, query);
      current = next;
      next.deadline =
          deadlines.schedule(
              () -> finish(next, terminal(next.generation, Status.TIMED_OUT), true),
              timeout.toNanos(),
              TimeUnit.NANOSECONDS);
      // At most one physical call plus the latest queued generation. Cancelling an uncooperative
      // adapter never opens another worker or starts an overlapping provider call.
      worker.execute(next.task);
    }
    if (previous != null)
      previous.answer.complete(terminal(previous.generation, Status.SUPERSEDED));
    return next.answer.minimalCompletionStage();
  }

  boolean cancel(long generation) {
    requireGeneration(generation);
    Flight cancelled;
    synchronized (lock) {
      if (closed || generation < lastGeneration) return false;
      if (generation > lastGeneration) {
        lastGeneration = generation;
        lastQuery = null; // Fence a delayed start for a screen already cancelled by its owner.
        lastRequirements = null;
      }
      cancelled = current;
      current = null;
      if (cancelled != null) stop(cancelled);
    }
    if (cancelled != null)
      cancelled.answer.complete(terminal(cancelled.generation, Status.CANCELLED));
    return cancelled != null;
  }

  private void execute(Flight flight) {
    synchronized (lock) {
      if (closed || current != flight) return;
    }
    if (Thread.currentThread().isInterrupted()) return;
    PlaceSearchResult result;
    try {
      result = adapter.search(flight.query);
      if (result == null)
        result = new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
    } catch (RuntimeException error) {
      // Neither upstream exception text nor input/location data is logged or exposed.
      result = new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
    }
    finish(flight, new Outcome(flight.generation, Status.RETURNED, result), false);
  }

  private void finish(Flight flight, Outcome outcome, boolean interrupt) {
    synchronized (lock) {
      if (current != flight) return; // Never deliver a late result to a newer screen generation.
      current = null;
      flight.deadline.cancel(false);
      if (interrupt) stop(flight);
    }
    // Client continuations can run synchronously; never invoke them while holding our state lock.
    flight.answer.complete(outcome);
  }

  private void stop(Flight flight) {
    flight.deadline.cancel(false);
    flight.task.cancel(true);
    worker.remove(flight.task);
  }

  @Override
  public void close() {
    Flight pending;
    synchronized (lock) {
      if (closed) return;
      closed = true;
      pending = current;
      current = null;
      lastQuery = null;
      lastRequirements = null;
      if (pending != null) stop(pending);
    }
    worker.shutdownNow();
    deadlines.shutdownNow();
    if (pending != null) pending.answer.complete(terminal(pending.generation, Status.CLOSED));
  }

  private static void requireGeneration(long generation) {
    if (generation < 1) throw new IllegalArgumentException("Invalid preview generation");
  }

  static void requireOutsideTransaction() {
    // Offloading must not hide a transaction that the waiting caller still holds open.
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Place preview must start outside a transaction");
  }

  private static Outcome terminal(long generation, Status status) {
    return new Outcome(generation, status, null);
  }

  private static CompletionStage<Outcome> completed(long generation, Status status) {
    return CompletableFuture.completedStage(terminal(generation, status));
  }

  private final class Flight {
    final long generation;
    final PlaceQuery query;
    final CompletableFuture<Outcome> answer = new CompletableFuture<>();
    final FutureTask<Void> task;
    ScheduledFuture<?> deadline;

    Flight(long generation, PlaceQuery query) {
      this.generation = generation;
      this.query = query;
      task =
          new FutureTask<>(
              () -> {
                execute(this);
                return null;
              });
    }
  }
}
