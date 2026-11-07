package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
import static ai.pleos.assistant.PlacePreviewSession.Status.RETURNED;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:preview;DB_CLOSE_DELAY=-1")
@Timeout(12)
class PlacePreviewTransactionTest {
  private static final PlaceQuery QUERY = new PlaceQuery(37.544, 127.055, 1000, 15);
  private static final PlaceSearchResult.Success EMPTY =
      new PlaceSearchResult.Success(List.of(), Instant.parse("2026-11-07T03:00:00Z"), 0);
  @Autowired PlatformTransactionManager transactions;
  @Autowired WorkflowService workflow;
  @Autowired Store store;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;

  @Test
  void startingInsideADatabaseTransactionIsRejectedBeforeAnyAdapterCall() {
    AtomicInteger calls = new AtomicInteger();
    try (var session =
        new PlacePreviewSession(
            query -> {
              calls.incrementAndGet();
              return EMPTY;
            },
            Duration.ofSeconds(3))) {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              transaction -> {
                store.lock();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThatThrownBy(() -> session.start(1, QUERY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Place preview must start outside a transaction");
              });
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void delayedPreviewDoesNotBlockAnExistingWorkflowOrPersistPlaceResponses() throws Exception {
    workflow.search(UUID.randomUUID().toString(), request()); // Warm the real H2 workflow first.
    List<Long> before = databaseCounts();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean workerTransaction = new AtomicBoolean(true);
    PlaceSearchAdapter delayed =
        query -> {
          workerTransaction.set(
              TransactionSynchronizationManager.isActualTransactionActive()
                  || TransactionSynchronizationManager.isSynchronizationActive());
          entered.countDown();
          awaitRelease(release);
          return EMPTY;
        };
    try (var session = new PlacePreviewSession(delayed, Duration.ofSeconds(3));
        var caller = Executors.newSingleThreadExecutor()) {
      try {
        var preview = session.start(1, QUERY).toCompletableFuture();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(workerTransaction).isFalse();
        assertThat(databaseCounts()).isEqualTo(before);
        var command = caller.submit(() -> workflow.search(UUID.randomUUID().toString(), request()));
        assertThat(command.get(2, TimeUnit.SECONDS).candidates).hasSize(3);
        assertThat(preview.isDone()).isFalse();
        release.countDown();
        assertThat(preview.get(2, TimeUnit.SECONDS).status()).isEqualTo(RETURNED);
        List<Long> after = databaseCounts();
        assertThat(after)
            .containsExactly(
                before.get(0) + 1, before.get(1) + 1, before.get(2), before.get(3), before.get(4));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void anotherTransactionHoldingTheMutexDoesNotPreventThePreviewFromReturning() throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var holder = Executors.newSingleThreadExecutor();
        var session = new PlacePreviewSession(query -> EMPTY, Duration.ofSeconds(3))) {
      var holding =
          holder.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          transaction -> {
                            store.lock();
                            locked.countDown();
                            awaitRelease(release);
                          }));
      try {
        assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(session.start(1, QUERY).toCompletableFuture().get(2, TimeUnit.SECONDS).status())
            .isEqualTo(RETURNED);
      } finally {
        release.countDown();
      }
      holding.get(2, TimeUnit.SECONDS);
    }
  }

  private List<Long> databaseCounts() {
    return List.of("searches", "commands", "reservations", "calls", "events").stream()
        .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
        .toList();
  }

  private SearchRequest request() {
    Instant now = clock.instant();
    return new SearchRequest(
        new Navigation(
            UUID.randomUUID().toString(),
            new Destination("가상 미리보기 목적지", QUERY.latitude(), QUERY.longitude()),
            now.plusSeconds(3600),
            now,
            NavigationSource.MOCK),
        new Party(2, 0),
        15,
        Set.of(),
        Set.of(),
        false);
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Test was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
