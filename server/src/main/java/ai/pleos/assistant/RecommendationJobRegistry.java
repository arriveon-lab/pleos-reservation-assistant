package ai.pleos.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Transient, owner-scoped recommendation work. Polling reads a completed result and never starts
 * another provider request. The caller owns the bounded executor and must close this registry when
 * its session ends.
 */
public final class RecommendationJobRegistry<T> implements AutoCloseable {
  public enum State {
    QUEUED,
    RUNNING,
    RESULTS,
    FAILED,
    CANCELLED
  }

  public record Snapshot<T>(UUID id, State state, T result, Instant validUntil) {
    @Override
    public String toString() {
      return "RecommendationJobSnapshot[state=" + state + ", private result omitted]";
    }
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(BooleanSupplier current) throws Exception;
  }

  private record View(String owner, UUID id) {}

  private static final class Job<T> {
    final UUID id = UUID.randomUUID();
    final View view;
    final long generation;
    final Instant validUntil;
    State state = State.QUEUED;
    T result;

    Job(View view, long generation, Instant validUntil) {
      this.view = view;
      this.generation = generation;
      this.validUntil = validUntil;
    }
  }

  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_JOBS = 64;
  private final Clock clock;
  private final Executor executor;
  private final Map<UUID, Job<T>> jobs = new HashMap<>();
  private final Map<View, Job<T>> currentByView = new HashMap<>();
  private boolean closed;

  public RecommendationJobRegistry(Clock clock, Executor executor) {
    this.clock = Objects.requireNonNull(clock);
    this.executor = Objects.requireNonNull(executor);
  }

  public synchronized UUID submit(String owner, UUID viewId, long generation, Work<T> work) {
    if (closed) throw new IllegalStateException("RECOMMENDATION_CLOSED");
    if (owner == null || owner.isBlank() || viewId == null || generation < 1 || work == null)
      throw new IllegalArgumentException("Invalid recommendation job");
    reap();
    if (jobs.size() >= MAX_JOBS) throw new IllegalStateException("RECOMMENDATION_CAPACITY");
    View view = new View(owner, viewId);
    Job<T> previous = currentByView.get(view);
    // An invalidated view still owns its generation until the job expires. Otherwise a delayed
    // request could resurrect the same destination after the screen has already closed.
    if (jobs.values().stream().anyMatch(j -> j.view.equals(view) && generation <= j.generation))
      throw new IllegalArgumentException("Stale recommendation generation");
    Job<T> job = new Job<>(view, generation, clock.instant().plus(LIFETIME));
    if (previous != null) cancel(previous);
    jobs.put(job.id, job);
    currentByView.put(view, job);
    try {
      executor.execute(() -> execute(job, work));
    } catch (RuntimeException rejected) {
      currentByView.remove(view, job);
      jobs.remove(job.id);
      throw new IllegalStateException("RECOMMENDATION_CAPACITY", rejected);
    }
    return job.id;
  }

  public synchronized Optional<Snapshot<T>> poll(String owner, UUID id) {
    reap();
    Job<T> job = jobs.get(id);
    if (job == null || !job.view.owner.equals(owner)) return Optional.empty();
    return Optional.of(new Snapshot<>(job.id, job.state, job.result, job.validUntil));
  }

  public synchronized void invalidate(String owner, UUID viewId) {
    View view = new View(owner, viewId);
    Job<T> job = currentByView.remove(view);
    if (job != null) cancel(job);
  }

  private void execute(Job<T> job, Work<T> work) {
    synchronized (this) {
      if (!current(job)) return;
      job.state = State.RUNNING;
    }
    T result;
    try {
      result = work.run(() -> isCurrent(job));
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      finish(job, null);
      return;
    } catch (Exception failed) {
      finish(job, null);
      return;
    }
    finish(job, result);
  }

  private synchronized void finish(Job<T> job, T result) {
    if (!current(job)) return;
    job.result = result;
    job.state = result == null ? State.FAILED : State.RESULTS;
  }

  private synchronized boolean isCurrent(Job<T> job) {
    return current(job);
  }

  private boolean current(Job<T> job) {
    return !closed
        && currentByView.get(job.view) == job
        && job.state != State.CANCELLED
        && clock.instant().isBefore(job.validUntil);
  }

  private static <T> void cancel(Job<T> job) {
    job.state = State.CANCELLED;
    job.result = null;
  }

  private void reap() {
    Instant now = clock.instant();
    for (Job<T> job : jobs.values().stream().filter(j -> !now.isBefore(j.validUntil)).toList()) {
      jobs.remove(job.id);
      currentByView.remove(job.view, job);
      cancel(job);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    jobs.values().forEach(RecommendationJobRegistry::cancel);
    jobs.clear();
    currentByView.clear();
  }
}
