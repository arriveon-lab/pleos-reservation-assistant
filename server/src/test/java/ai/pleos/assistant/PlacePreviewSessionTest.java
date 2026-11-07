package ai.pleos.assistant;

import static ai.pleos.assistant.PlacePreviewSession.Status.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class PlacePreviewSessionTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private static final PlaceQuery FIRST = new PlaceQuery(37.544, 127.055, 1000, 15);
  private static final PlaceQuery SECOND = new PlaceQuery(37.548, 127.047, 1000, 15);
  private static final PlaceQuery THIRD = new PlaceQuery(37.541, 127.043, 1000, 15);
  private static final Duration DEADLINE = Duration.ofSeconds(3);
  private static final PlaceSearchResult.Success EMPTY =
      new PlaceSearchResult.Success(List.of(), NOW, 0);

  private PlacePreviewSession.Outcome await(CompletionStage<PlacePreviewSession.Outcome> stage)
      throws Exception {
    return stage.toCompletableFuture().get(4, TimeUnit.SECONDS);
  }

  @Test
  void emptySuccessIsNotCachedAndReplayingACompletedGenerationDoesNotCallAgain() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var session =
        new PlacePreviewSession(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            DEADLINE)) {
      assertThat(await(session.start(1, FIRST)))
          .isEqualTo(new PlacePreviewSession.Outcome(1, RETURNED, EMPTY));
      assertThat(await(session.start(1, FIRST)).status()).isEqualTo(ALREADY_FINISHED);
      assertThat(await(session.start(1, SECOND)).status()).isEqualTo(REQUEST_CONFLICT);
      assertThat(calls).hasValue(1);
      assertThat(await(session.start(2, FIRST)).status()).isEqualTo(RETURNED);
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void simultaneousSameGenerationCallsShareOneAdapterCallAndClientCancellationIsIsolated()
      throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, DEADLINE);
        var callers = Executors.newFixedThreadPool(8)) {
      var first = session.start(1, FIRST);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      var futures =
          new ArrayList<
              java.util.concurrent.Future<CompletionStage<PlacePreviewSession.Outcome>>>();
      for (int i = 0; i < 8; i++) futures.add(callers.submit(() -> session.start(1, FIRST)));
      var views = new ArrayList<CompletionStage<PlacePreviewSession.Outcome>>();
      for (var f : futures) views.add(f.get(2, TimeUnit.SECONDS));
      assertThat(first.toCompletableFuture().cancel(true)).isTrue();
      assertThat(await(session.start(1, SECOND)).status()).isEqualTo(REQUEST_CONFLICT);
      fake.release.countDown();
      for (var view : views) assertThat(await(view).result()).isEqualTo(EMPTY);
      assertThat(fake.queries).containsExactly(FIRST);
    }
  }

  @Test
  void supersedingAnUncooperativeCallKeepsOnlyTheLatestQueuedQueryAndDiscardsOldResults()
      throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, DEADLINE)) {
      var old = session.start(1, FIRST);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      var replacedBeforeStarting = session.start(2, SECOND);
      var latest = session.start(3, THIRD);
      assertThat(await(old).status()).isEqualTo(SUPERSEDED);
      assertThat(await(replacedBeforeStarting).status()).isEqualTo(SUPERSEDED);
      assertThat(await(session.start(2, SECOND)).status()).isEqualTo(STALE_GENERATION);
      assertThat(fake.queries).containsExactly(FIRST);
      fake.release.countDown();
      assertThat(await(latest)).isEqualTo(new PlacePreviewSession.Outcome(3, RETURNED, EMPTY));
      assertThat(fake.queries).containsExactly(FIRST, THIRD);
      assertThat(fake.maxActive).hasValue(1);
      assertThat(await(old).status()).isEqualTo(SUPERSEDED);
    }
  }

  @Test
  void cancellingTheCurrentGenerationInterruptsButNeverResendsOrPublishesItsLateResult()
      throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, DEADLINE)) {
      var answer = session.start(1, FIRST);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(session.cancel(1)).isTrue();
      assertThat(fake.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(await(answer).status()).isEqualTo(CANCELLED);
      assertThat(session.cancel(1)).isFalse();
      assertThat(await(session.start(1, FIRST)).status()).isEqualTo(ALREADY_FINISHED);
      fake.release.countDown();
      assertThat(fake.exited.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(await(answer).status()).isEqualTo(CANCELLED);
      assertThat(fake.queries).containsExactly(FIRST);
    }
  }

  @Test
  void cancellationFencesARequestThatHasNotArrivedYetAndOlderCancelCannotStopNewWork()
      throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, DEADLINE)) {
      assertThat(session.cancel(2)).isFalse();
      assertThat(await(session.start(1, FIRST)).status()).isEqualTo(STALE_GENERATION);
      assertThat(await(session.start(2, FIRST)).status()).isEqualTo(ALREADY_FINISHED);
      var latest = session.start(3, SECOND);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(session.cancel(2)).isFalse();
      fake.release.countDown();
      assertThat(await(latest).status()).isEqualTo(RETURNED);
      assertThat(fake.queries).containsExactly(SECOND);
    }
  }

  @Test
  void timeoutAlsoBoundsQueuedWorkAndDoesNotStartItAfterTheOldCallFinallyReturns()
      throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, Duration.ofMillis(300))) {
      var first = session.start(1, FIRST);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(await(first).status()).isEqualTo(TIMED_OUT);
      var queued = session.start(2, SECOND);
      assertThat(await(queued).status()).isEqualTo(TIMED_OUT);
      assertThat(await(session.start(2, SECOND)).status()).isEqualTo(ALREADY_FINISHED);
      fake.release.countDown();
      assertThat(fake.exited.await(2, TimeUnit.SECONDS)).isTrue();
      // A fresh explicit generation recovers. It cannot be confused with the timed-out queued task.
      assertThat(await(session.start(3, THIRD)).status()).isEqualTo(RETURNED);
      assertThat(fake.queries).containsExactly(FIRST, THIRD);
      assertThat(fake.maxActive).hasValue(1);
    }
  }

  @ParameterizedTest
  @EnumSource(PlaceSearchFailure.class)
  void providerFailuresKeepTheirKindAndNeverTurnIntoAnEmptySuccess(PlaceSearchFailure kind)
      throws Exception {
    var failure = new PlaceSearchResult.Failure(kind);
    try (var session = new PlacePreviewSession(query -> failure, DEADLINE)) {
      assertThat(await(session.start(1, FIRST)).result()).isEqualTo(failure);
      assertThat(await(session.start(1, FIRST)).status()).isEqualTo(ALREADY_FINISHED);
    }
  }

  @Test
  void invalidAndExceptionalAdaptersReturnSafeFailures() throws Exception {
    try (var session = new PlacePreviewSession(query -> null, DEADLINE)) {
      assertThat(await(session.start(1, FIRST)).result())
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
    }
    try (var session =
        new PlacePreviewSession(
            query -> {
              throw new IllegalStateException("fictional-sensitive-upstream-detail");
            },
            DEADLINE)) {
      var answer = await(session.start(1, FIRST));
      assertThat(answer.result())
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE));
      assertThat(answer.toString()).doesNotContain("fictional-sensitive-upstream-detail");
    }
  }

  @Test
  void closeCompletesPendingCallAndQueuedQueryWithoutStartingMoreWork() throws Exception {
    try (var fake = new BlockingAdapter();
        var session = new PlacePreviewSession(fake, DEADLINE)) {
      var first = session.start(1, FIRST);
      assertThat(fake.entered.await(2, TimeUnit.SECONDS)).isTrue();
      var queued = session.start(2, SECOND);
      session.close();
      session.close();
      assertThat(await(first).status()).isEqualTo(SUPERSEDED);
      assertThat(await(queued).status()).isEqualTo(CLOSED);
      assertThat(await(session.start(3, THIRD)).status()).isEqualTo(CLOSED);
      fake.release.countDown();
      assertThat(fake.exited.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(fake.queries).containsExactly(FIRST);
    }
  }

  @Test
  void resultsPreserveProviderAndPartialQualityWithoutCreatingBookingFacts() throws Exception {
    PlaceSummary place =
        new PlaceSummary(
            PlaceSummary.Provider.MOCK,
            "fictional-preview",
            "가상 미리보기 식당",
            null,
            37.544,
            127.055,
            null,
            null,
            null,
            null);
    var partial = new PlaceSearchResult.Success(List.of(place), NOW, 2);
    try (var session = new PlacePreviewSession(query -> partial, DEADLINE)) {
      assertThat(await(session.start(1, FIRST)).result()).isEqualTo(partial);
      assertThat(partial.places().getFirst().provider()).isEqualTo(PlaceSummary.Provider.MOCK);
      assertThat(partial.rejectedRecords()).isEqualTo(2);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void invalidGenerationsAreRejectedBeforeStarting(long generation) {
    AtomicBoolean called = new AtomicBoolean();
    try (var session =
        new PlacePreviewSession(
            query -> {
              called.set(true);
              return EMPTY;
            },
            DEADLINE)) {
      assertThatThrownBy(() -> session.start(generation, FIRST))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> session.cancel(generation))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(called).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 3001})
  void invalidTimeoutsDoNotCreateAnExecutionSession(long millis) {
    assertThatThrownBy(() -> new PlacePreviewSession(query -> EMPTY, Duration.ofMillis(millis)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class BlockingAdapter implements PlaceSearchAdapter, AutoCloseable {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch exited = new CountDownLatch(1);
    final List<PlaceQuery> queries = new CopyOnWriteArrayList<>();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maxActive = new AtomicInteger();

    @Override
    public PlaceSearchResult search(PlaceQuery query) {
      queries.add(query);
      maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
      entered.countDown();
      try {
        boolean done = false;
        while (!done) {
          try {
            done = release.await(5, TimeUnit.SECONDS);
            if (!done)
              throw new IllegalStateException("Test did not release the fictional adapter");
          } catch (InterruptedException ignored) {
            interrupted.countDown(); // Deliberately ignore cancellation to exercise late responses.
          }
        }
        return EMPTY;
      } finally {
        active.decrementAndGet();
        exited.countDown();
      }
    }

    @Override
    public void close() {
      release.countDown();
    }
  }
}
