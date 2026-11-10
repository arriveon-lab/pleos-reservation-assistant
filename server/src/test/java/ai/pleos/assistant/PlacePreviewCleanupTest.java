package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Timeout(10)
class PlacePreviewCleanupTest {
  static final PlacePreviewRegistry.Owner OWNER = new PlacePreviewRegistry.Owner("fictional-owner");
  static final PlaceQuery QUERY = new PlaceQuery(37.544, 127.055, 1000, 3);
  static final PlaceSearchResult.Success EMPTY =
      new PlaceSearchResult.Success(List.of(), Instant.parse("2026-11-08T00:00:00Z"), 0);

  static PlacePreviewRegistry.Limits limits(Duration idle, int budget) {
    return new PlacePreviewRegistry.Limits(2, 2, 1, budget, idle, Duration.ofSeconds(3));
  }

  static void notFound(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            PlacePreviewRegistry.AccessRejected.class,
            error ->
                assertThat(error.reason()).isEqualTo(PlacePreviewRegistry.Rejection.NOT_FOUND));
  }

  @Test
  void runtimeConstructorRemovesIdleSessionsWithoutAnyNewRequestOrExplicitReap() {
    AtomicInteger calls = new AtomicInteger();
    var registry =
        new PlacePreviewRegistry(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            limits(Duration.ofMillis(60), 2));
    try {
      UUID id = registry.open(OWNER);
      await().atMost(Duration.ofSeconds(3)).until(() -> registry.statistics().sessions() == 0);
      notFound(() -> registry.start(OWNER, id, 1, QUERY));
      assertThat(registry.statistics().dispatches()).isZero();
      assertThat(calls).hasValue(0);
      assertThat(registry.hasCleanupFailed()).isFalse();
    } finally {
      registry.close();
    }
    await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
  }

  @Test
  void realSchedulerHonoursMonotonicLeaseRenewalAndExactExpiry() {
    AtomicLong time = new AtomicLong();
    try (var registry =
        new PlacePreviewRegistry(
            query -> EMPTY, limits(Duration.ofSeconds(10), 2), time::get, Duration.ofMillis(2))) {
      UUID id = registry.open(OWNER);
      time.set(Duration.ofSeconds(9).toNanos());
      registry.cancel(OWNER, id, 1);
      time.set(Duration.ofSeconds(10).toNanos());
      await()
          .during(Duration.ofMillis(30))
          .atMost(Duration.ofSeconds(1))
          .until(() -> registry.statistics().sessions() == 1);
      time.set(Duration.ofSeconds(19).toNanos());
      await().atMost(Duration.ofSeconds(2)).until(() -> registry.statistics().sessions() == 0);
      notFound(() -> registry.close(OWNER, id));
    }
  }

  @Test
  void expiryDoesNotReleaseAnUncooperativeProviderSlotOrRefundItsBudget() throws Exception {
    AtomicLong time = new AtomicLong();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    var registry =
        new PlacePreviewRegistry(
            query -> {
              entered.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                  /* Simulated uncooperative IO. */
                }
              }
              return EMPTY;
            },
            limits(Duration.ofSeconds(10), 2),
            time::get,
            Duration.ofMillis(2));
    try {
      UUID id = registry.open(OWNER);
      var pending = registry.start(OWNER, id, 1, QUERY);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      time.set(Duration.ofSeconds(10).toNanos());
      assertThat(pending.toCompletableFuture().get(2, TimeUnit.SECONDS).status())
          .isEqualTo(PlacePreviewSession.Status.CLOSED);
      assertThat(registry.statistics().runningCalls()).isEqualTo(1);
      assertThat(registry.statistics().dispatches()).isEqualTo(1);
      UUID next = registry.open(OWNER);
      var rejected =
          registry.start(OWNER, next, 1, QUERY).toCompletableFuture().get(1, TimeUnit.SECONDS);
      assertThat(rejected.result())
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.RATE_LIMITED));
      assertThat(registry.statistics().dispatches()).isEqualTo(1);
    } finally {
      registry.close();
      release.countDown();
    }
    await().atMost(Duration.ofSeconds(2)).until(() -> registry.statistics().runningCalls() == 0);
    await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
  }

  @Test
  void expiredCompletedSessionsCannotResetLifetimeDispatchBudget() throws Exception {
    AtomicLong time = new AtomicLong();
    try (var registry =
        new PlacePreviewRegistry(
            query -> EMPTY, limits(Duration.ofSeconds(10), 1), time::get, Duration.ofMillis(2))) {
      UUID first = registry.open(OWNER);
      registry.start(OWNER, first, 1, QUERY).toCompletableFuture().get(1, TimeUnit.SECONDS);
      time.set(Duration.ofSeconds(10).toNanos());
      await().atMost(Duration.ofSeconds(2)).until(() -> registry.statistics().sessions() == 0);
      UUID second = registry.open(OWNER);
      var result =
          registry.start(OWNER, second, 1, QUERY).toCompletableFuture().get(1, TimeUnit.SECONDS);
      assertThat(result.result())
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.RATE_LIMITED));
      assertThat(registry.statistics().dispatches()).isEqualTo(1);
    }
  }

  @Test
  void callbackCanCloseRegistryFromCleanupThreadWithoutDeadlock() throws Exception {
    AtomicLong time = new AtomicLong();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    var registry =
        new PlacePreviewRegistry(
            query -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
              }
              return EMPTY;
            },
            limits(Duration.ofSeconds(10), 2),
            time::get,
            Duration.ofMillis(2));
    try {
      var pending = registry.start(OWNER, registry.open(OWNER), 1, QUERY);
      var callback = pending.thenRun(registry::close);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      time.set(Duration.ofSeconds(10).toNanos());
      callback.toCompletableFuture().get(2, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
      assertThat(registry.statistics().sessions()).isZero();
    } finally {
      release.countDown();
      registry.close();
    }
  }

  @Test
  void cleanupFailureClosesAdmissionInsteadOfSilentlyStoppingTheTimer() {
    AtomicBoolean fail = new AtomicBoolean();
    var registry =
        new PlacePreviewRegistry(
            query -> EMPTY,
            limits(Duration.ofSeconds(10), 2),
            () -> {
              if (fail.get()) throw new IllegalStateException("private failure text");
              return 0;
            },
            Duration.ofMillis(2));
    try {
      registry.open(OWNER);
      fail.set(true);
      await().atMost(Duration.ofSeconds(2)).until(registry::hasCleanupFailed);
      await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
      assertThat(registry.statistics().sessions()).isZero();
      assertThatThrownBy(() -> registry.open(OWNER))
          .isInstanceOfSatisfying(
              PlacePreviewRegistry.AccessRejected.class,
              error -> assertThat(error.reason()).isEqualTo(PlacePreviewRegistry.Rejection.CLOSED))
          .hasMessageNotContaining("private failure text");
    } finally {
      registry.close();
    }
  }

  @Test
  void concurrentCloseAndSweepsAreIdempotentAndReleaseCleanupExecutor() throws Exception {
    AtomicLong time = new AtomicLong();
    var registry =
        new PlacePreviewRegistry(
            query -> EMPTY, limits(Duration.ofSeconds(10), 2), time::get, Duration.ofMillis(2));
    registry.open(OWNER);
    try (var callers = Executors.newFixedThreadPool(4)) {
      time.set(Duration.ofSeconds(10).toNanos());
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 20; i++) tasks.add(callers.submit(() -> registry.close()));
      for (var task : tasks) task.get(2, TimeUnit.SECONDS);
    } finally {
      registry.close();
    }
    await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
    assertThat(registry.statistics().sessions()).isZero();
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnedRegistry {
    @Bean(destroyMethod = "close")
    PlacePreviewRegistry registry() {
      return new PlacePreviewRegistry(query -> EMPTY, limits(Duration.ofMinutes(5), 2));
    }
  }

  @Test
  void springContextShutdownClosesOwnedRegistryAndItsCleanupThread() {
    var context = new AnnotationConfigApplicationContext(OwnedRegistry.class);
    PlacePreviewRegistry registry = context.getBean(PlacePreviewRegistry.class);
    registry.open(OWNER);
    context.close();
    await().atMost(Duration.ofSeconds(2)).until(registry::isCleanupTerminated);
    assertThat(registry.statistics().sessions()).isZero();
    assertThatThrownBy(() -> registry.open(OWNER))
        .isInstanceOf(PlacePreviewRegistry.AccessRejected.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1001})
  void invalidCleanupIntervalIsRejectedBeforeCreatingAThread(long millis) {
    assertThatThrownBy(
            () ->
                new PlacePreviewRegistry(
                    query -> EMPTY,
                    limits(Duration.ofSeconds(10), 2),
                    System::nanoTime,
                    Duration.ofMillis(millis)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
