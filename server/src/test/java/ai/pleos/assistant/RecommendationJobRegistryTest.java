package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecommendationJobRegistryTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void pollNeverRepeatsWorkAndSupersededResultStaysHidden() {
    var queue = new ArrayDeque<Runnable>();
    Executor executor = queue::add;
    var calls = new AtomicInteger();
    try (var jobs = new RecommendationJobRegistry<String>(CLOCK, executor)) {
      UUID view = UUID.randomUUID();
      UUID first =
          jobs.submit(
              "owner-a",
              view,
              1,
              current -> {
                calls.incrementAndGet();
                return "old";
              });
      assertThat(jobs.poll("owner-a", first).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.QUEUED);
      assertThat(jobs.poll("owner-b", first)).isEmpty();
      UUID second =
          jobs.submit(
              "owner-a",
              view,
              2,
              current -> {
                calls.incrementAndGet();
                return "new";
              });
      queue.remove().run();
      assertThat(calls).hasValue(0);
      assertThat(jobs.poll("owner-a", first).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.CANCELLED);
      queue.remove().run();
      assertThat(jobs.poll("owner-a", second).orElseThrow().result()).isEqualTo("new");
      assertThat(jobs.poll("owner-a", second).orElseThrow().result()).isEqualTo("new");
      assertThat(calls).hasValue(1);
      assertThatThrownBy(() -> jobs.submit("owner-a", view, 2, current -> "replay"))
          .isInstanceOf(IllegalArgumentException.class);
      jobs.invalidate("owner-a", view);
      assertThat(jobs.poll("owner-a", second).orElseThrow().result()).isNull();
    }
  }

  @Test
  void invalidationDuringWorkDiscardsLateResultAndFailureDoesNotRetry() {
    var queue = new ArrayDeque<Runnable>();
    try (var jobs = new RecommendationJobRegistry<String>(CLOCK, queue::add)) {
      UUID view = UUID.randomUUID();
      UUID late =
          jobs.submit(
              "owner-a",
              view,
              1,
              current -> {
                assertThat(current.getAsBoolean()).isTrue();
                jobs.invalidate("owner-a", view);
                assertThat(current.getAsBoolean()).isFalse();
                return "late";
              });
      queue.remove().run();
      assertThat(jobs.poll("owner-a", late).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.CANCELLED);
      assertThat(jobs.poll("owner-a", late).orElseThrow().result()).isNull();

      UUID failed =
          jobs.submit(
              "owner-a",
              view,
              2,
              current -> {
                throw new IllegalStateException("provider failed");
              });
      queue.remove().run();
      assertThat(jobs.poll("owner-a", failed).orElseThrow().state())
          .isEqualTo(RecommendationJobRegistry.State.FAILED);
      assertThat(jobs.poll("owner-a", failed).orElseThrow().result()).isNull();
    }
  }
}
