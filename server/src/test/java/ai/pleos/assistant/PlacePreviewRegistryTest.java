package ai.pleos.assistant;

import static ai.pleos.assistant.PlacePreviewRegistry.Rejection.*;
import static ai.pleos.assistant.PlacePreviewSession.Status.CLOSED;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class PlacePreviewRegistryTest {
  private static final PlacePreviewRegistry.Owner ALICE =
      new PlacePreviewRegistry.Owner("fictional-owner-a");
  private static final PlacePreviewRegistry.Owner BOB =
      new PlacePreviewRegistry.Owner("fictional-owner-b");
  private static final PlaceQuery QUERY = new PlaceQuery(37.544, 127.055, 1000, 15);
  private static final PlaceSearchResult.Success EMPTY =
      new PlaceSearchResult.Success(List.of(), Instant.parse("2026-11-07T03:00:00Z"), 0);
  private static final Duration IDLE = Duration.ofSeconds(10);

  private PlacePreviewRegistry.Limits limits(
      int sessions, int perOwner, int concurrency, int budget) {
    return new PlacePreviewRegistry.Limits(
        sessions, perOwner, concurrency, budget, IDLE, Duration.ofSeconds(3));
  }

  private PlacePreviewSession.Outcome result(CompletionStage<PlacePreviewSession.Outcome> stage)
      throws Exception {
    return stage.toCompletableFuture().get(4, TimeUnit.SECONDS);
  }

  private void rejected(Runnable action, PlacePreviewRegistry.Rejection reason) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            PlacePreviewRegistry.AccessRejected.class,
            error -> {
              assertThat(error.reason()).isEqualTo(reason);
              assertThat(error.getMessage()).isEqualTo("Preview access rejected: " + reason);
            });
  }

  private void limited(PlacePreviewSession.Outcome outcome) {
    assertThat(outcome.result())
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.RATE_LIMITED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"start", "cancel", "close"})
  void aForeignOwnerCannotReadCancelOrCloseAndGetsTheSameErrorAsAnUnknownId(String operation)
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            limits(4, 2, 2, 10))) {
      UUID id = registry.open(ALICE);
      for (UUID target : List.of(id, UUID.randomUUID())) {
        rejected(
            () -> {
              switch (operation) {
                case "start" -> registry.start(BOB, target, 1, QUERY);
                case "cancel" -> registry.cancel(BOB, target, 1);
                case "close" -> registry.close(BOB, target);
                default -> throw new AssertionError();
              }
            },
            NOT_FOUND);
      }
      assertThat(calls).hasValue(0);
      assertThat(result(registry.start(ALICE, id, 1, QUERY)).result()).isEqualTo(EMPTY);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void globalAndPerOwnerSessionLimitsAreCheckedBeforeAllocatingAnotherSession() {
    try (var registry = new PlacePreviewRegistry(query -> EMPTY, limits(3, 2, 1, 10))) {
      UUID first = registry.open(ALICE);
      registry.open(ALICE);
      rejected(() -> registry.open(ALICE), SESSION_LIMIT);
      registry.open(BOB);
      rejected(() -> registry.open(BOB), SESSION_LIMIT);
      assertThat(registry.statistics().sessions()).isEqualTo(3);
      registry.close(ALICE, first);
      registry.open(ALICE);
      assertThat(registry.statistics().sessions()).isEqualTo(3);
      rejected(() -> registry.start(ALICE, first, 1, QUERY), NOT_FOUND);
    }
  }

  @Test
  void concurrentSessionCreationCannotExceedTheLimit() throws Exception {
    try (var registry = new PlacePreviewRegistry(query -> EMPTY, limits(4, 4, 2, 10));
        var callers = Executors.newFixedThreadPool(8)) {
      var requests = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 16; i++)
        requests.add(
            callers.submit(
                () -> {
                  try {
                    registry.open(ALICE);
                    return true;
                  } catch (PlacePreviewRegistry.AccessRejected rejection) {
                    assertThat(rejection.reason()).isEqualTo(SESSION_LIMIT);
                    return false;
                  }
                }));
      int accepted = 0;
      for (var request : requests) if (request.get(2, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(4);
      assertThat(registry.statistics()).isEqualTo(new PlacePreviewRegistry.Statistics(4, 0, 0));
    }
  }

  @Test
  void idleExpiryUsesAnExactMonotonicBoundaryAndForeignAccessCannotExtendIt() throws Exception {
    AtomicLong tick = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    try (var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            limits(1, 1, 1, 10),
            tick::get)) {
      UUID id = registry.open(ALICE);
      tick.set(Duration.ofSeconds(9).toNanos());
      result(registry.start(ALICE, id, 1, QUERY));
      tick.set(Duration.ofSeconds(18).toNanos());
      rejected(() -> registry.start(BOB, id, 2, QUERY), NOT_FOUND);
      tick.set(Duration.ofSeconds(19).toNanos());
      rejected(() -> registry.start(ALICE, id, 2, QUERY), NOT_FOUND);
      assertThat(registry.statistics().sessions()).isZero();
      UUID replacement = registry.open(ALICE);
      assertThat(replacement).isNotEqualTo(id);
      rejected(() -> registry.cancel(ALICE, id, 2), NOT_FOUND);
      result(registry.start(ALICE, replacement, 1, QUERY));
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void invalidRequestsCannotRenewALeaseOrConsumeDispatchBudget() {
    AtomicLong tick = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    try (var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            limits(1, 1, 1, 10),
            tick::get)) {
      UUID id = registry.open(ALICE);
      tick.set(Duration.ofSeconds(9).toNanos());
      assertThatThrownBy(() -> registry.start(ALICE, id, 0, QUERY))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> registry.cancel(ALICE, id, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> registry.start(ALICE, id, 1, null))
          .isInstanceOf(NullPointerException.class);
      tick.set(IDLE.toNanos());
      assertThat(registry.reapExpired()).isEqualTo(1);
      assertThat(registry.statistics()).isEqualTo(new PlacePreviewRegistry.Statistics(0, 0, 0));
      assertThat(calls).hasValue(0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closingOrExpiringAnUncooperativeCallDoesNotReleaseItsGlobalPermitEarly(boolean expire)
      throws Exception {
    AtomicLong tick = new AtomicLong();
    try (var fake = new BlockingAdapter(1);
        var registry = new PlacePreviewRegistry(fake, limits(1, 1, 1, 10), tick::get)) {
      UUID first = registry.open(ALICE);
      var pending = registry.start(ALICE, first, 1, QUERY);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      if (expire) {
        tick.set(IDLE.toNanos());
        assertThat(registry.reapExpired()).isEqualTo(1);
      } else registry.close(ALICE, first);
      assertThat(result(pending).status()).isEqualTo(CLOSED);
      assertThat(registry.statistics()).isEqualTo(new PlacePreviewRegistry.Statistics(0, 1, 1));
      UUID next = registry.open(BOB);
      limited(result(registry.start(BOB, next, 1, QUERY)));
      assertThat(fake.calls).hasValue(1);
      fake.release.countDown();
      await().atMost(Duration.ofSeconds(2)).until(() -> registry.statistics().runningCalls() == 0);
      assertThat(result(registry.start(BOB, next, 2, QUERY)).result()).isEqualTo(EMPTY);
      assertThat(fake.calls).hasValue(2);
      assertThat(fake.maxActive).hasValue(1);
      assertThat(registry.statistics().dispatches()).isEqualTo(2);
    }
  }

  @Test
  void globalConcurrencyIsSharedAcrossOwnersAndRequiresAnExplicitRetryAfterCapacityFailure()
      throws Exception {
    try (var fake = new BlockingAdapter(2);
        var registry = new PlacePreviewRegistry(fake, limits(3, 2, 2, 10))) {
      UUID a = registry.open(ALICE), b = registry.open(BOB), c = registry.open(ALICE);
      var first = registry.start(ALICE, a, 1, QUERY);
      var second = registry.start(BOB, b, 1, QUERY);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      limited(result(registry.start(ALICE, c, 1, QUERY)));
      assertThat(fake.calls).hasValue(2);
      fake.release.countDown();
      assertThat(result(first).result()).isEqualTo(EMPTY);
      assertThat(result(second).result()).isEqualTo(EMPTY);
      assertThat(result(registry.start(ALICE, c, 2, QUERY)).result()).isEqualTo(EMPTY);
      assertThat(fake.maxActive).hasValue(2);
      assertThat(registry.statistics().dispatches()).isEqualTo(3);
    }
  }

  @Test
  void concurrentDispatchesAndSessionRecreationCannotResetTheCumulativeBudget() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            limits(4, 4, 4, 2))) {
      var ids = new ArrayList<UUID>();
      var answers = new ArrayList<CompletionStage<PlacePreviewSession.Outcome>>();
      for (int i = 0; i < 4; i++) ids.add(registry.open(ALICE));
      for (UUID id : ids) answers.add(registry.start(ALICE, id, 1, QUERY));
      int successes = 0;
      for (var answer : answers) {
        var outcome = result(answer);
        if (outcome.result() instanceof PlaceSearchResult.Success) successes++;
        else limited(outcome);
      }
      assertThat(successes).isEqualTo(2);
      for (UUID id : ids) registry.close(ALICE, id);
      UUID replacement = registry.open(BOB);
      limited(result(registry.start(BOB, replacement, 1, QUERY)));
      assertThat(calls).hasValue(2);
      assertThat(registry.statistics().dispatches()).isEqualTo(2);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failuresAndExceptionsConsumeBudgetAndReleaseConcurrency(boolean exceptional)
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              if (exceptional) throw new IllegalStateException("fictional-private-error");
              return new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
            },
            limits(1, 1, 1, 1))) {
      UUID id = registry.open(ALICE);
      assertThat(result(registry.start(ALICE, id, 1, QUERY)).result())
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE));
      assertThat(registry.statistics().runningCalls()).isZero();
      registry.close(ALICE, id);
      UUID next = registry.open(ALICE);
      limited(result(registry.start(ALICE, next, 1, QUERY)));
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void joiningTheSameInFlightGenerationConsumesOnlyOneDispatch() throws Exception {
    try (var fake = new BlockingAdapter(1);
        var registry = new PlacePreviewRegistry(fake, limits(1, 1, 1, 1))) {
      UUID id = registry.open(ALICE);
      var first = registry.start(ALICE, id, 1, QUERY);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      var joined = registry.start(ALICE, id, 1, QUERY);
      fake.release.countDown();
      assertThat(result(first).result()).isEqualTo(EMPTY);
      assertThat(result(joined).result()).isEqualTo(EMPTY);
      limited(result(registry.start(ALICE, id, 2, QUERY)));
      assertThat(fake.calls).hasValue(1);
    }
  }

  @Test
  void restartingTheRegistryDoesNotRestoreOldHandlesOrAutomaticallyRepeatCalls() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    PlaceSearchAdapter fake =
        query -> {
          calls.incrementAndGet();
          return EMPTY;
        };
    UUID old;
    try (var registry = new PlacePreviewRegistry(fake, limits(1, 1, 1, 1))) {
      old = registry.open(ALICE);
      result(registry.start(ALICE, old, 1, QUERY));
    }
    try (var restarted = new PlacePreviewRegistry(fake, limits(1, 1, 1, 1))) {
      rejected(() -> restarted.start(ALICE, old, 1, QUERY), NOT_FOUND);
      assertThat(calls).hasValue(1);
      assertThat(restarted.statistics()).isEqualTo(new PlacePreviewRegistry.Statistics(0, 0, 0));
      UUID next = restarted.open(ALICE);
      assertThat(next).isNotEqualTo(old);
      result(restarted.start(ALICE, next, 1, QUERY));
      assertThat(calls).hasValue(2); // Budget is deliberately process-lifetime, not durable quota.
    }
  }

  @Test
  void closingRegistryCompletesWorkAndRejectsAllFurtherOperations() throws Exception {
    try (var fake = new BlockingAdapter(1);
        var registry = new PlacePreviewRegistry(fake, limits(1, 1, 1, 10))) {
      UUID id = registry.open(ALICE);
      var answer = registry.start(ALICE, id, 1, QUERY);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      registry.close();
      registry.close();
      assertThat(result(answer).status()).isEqualTo(CLOSED);
      rejected(() -> registry.open(ALICE), PlacePreviewRegistry.Rejection.CLOSED);
      rejected(() -> registry.start(ALICE, id, 2, QUERY), PlacePreviewRegistry.Rejection.CLOSED);
      rejected(() -> registry.cancel(ALICE, id, 2), PlacePreviewRegistry.Rejection.CLOSED);
      rejected(() -> registry.close(ALICE, id), PlacePreviewRegistry.Rejection.CLOSED);
      assertThat(registry.statistics().sessions()).isZero();
    }
  }

  @Test
  void expiryContinuationsCanReenterTheRegistryFromAnotherThreadWithoutHoldingItsLock()
      throws Exception {
    AtomicLong tick = new AtomicLong();
    try (var fake = new BlockingAdapter(1);
        var registry = new PlacePreviewRegistry(fake, limits(1, 1, 1, 10), tick::get);
        var caller = Executors.newSingleThreadExecutor()) {
      UUID id = registry.open(ALICE);
      var answer = registry.start(ALICE, id, 1, QUERY);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      var reentered =
          answer.thenApply(
              outcome -> {
                try {
                  return caller.submit(() -> registry.open(BOB)).get(2, TimeUnit.SECONDS);
                } catch (Exception error) {
                  throw new AssertionError(error);
                }
              });
      tick.set(IDLE.toNanos());
      assertThat(registry.reapExpired()).isEqualTo(1);
      assertThat(reentered.toCompletableFuture().get(2, TimeUnit.SECONDS)).isNotNull();
    }
  }

  @Test
  void invalidConfigurationAndOwnerScopesCannotDisableBounds() {
    assertThatThrownBy(() -> limits(65, 1, 1, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limits(1, 2, 1, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limits(1, 1, 2, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limits(1, 1, 1, 1001)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlacePreviewRegistry.Owner(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PlacePreviewRegistry.Limits(1, 1, 1, 1, Duration.ZERO, Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class BlockingAdapter implements PlaceSearchAdapter, AutoCloseable {
    final CountDownLatch entered;
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maxActive = new AtomicInteger();

    BlockingAdapter(int expectedCalls) {
      entered = new CountDownLatch(expectedCalls);
    }

    @Override
    public PlaceSearchResult search(PlaceQuery query) {
      calls.incrementAndGet();
      maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
      entered.countDown();
      try {
        boolean done = false;
        while (!done) {
          try {
            done = release.await(5, TimeUnit.SECONDS);
            if (!done) throw new IllegalStateException("Test was not released");
          } catch (InterruptedException ignored) {
            // Simulate a transport that continues consuming capacity after cancellation.
          }
        }
        return EMPTY;
      } finally {
        active.decrementAndGet();
      }
    }

    @Override
    public void close() {
      release.countDown();
    }
  }
}
