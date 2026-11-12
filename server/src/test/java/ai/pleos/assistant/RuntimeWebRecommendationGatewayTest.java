package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeWebRecommendationGatewayTest {
  static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void authenticatedRequestBudgetAllowsDifferentDestinationsWithoutRetry() throws Exception {
    var calls = new AtomicInteger();
    var json = new ObjectMapper();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) -> {
              assertThat(
                      json.readTree(json.readTree(body).path("input").asText())
                          .path("area")
                          .asText())
                  .isIn("서울숲", "광화문");
              calls.incrementAndGet();
              return new GptPlaceSourceDiscovery.Response(
                  200, MockWebRecommendationGateway.fictionalResponse());
            },
            () -> "fixture-key");
    var gateway = new RuntimeWebRecommendationGateway(clock, adapter, "gpt-6-luna", 3072, 2);
    for (String area : new String[] {"서울숲", "광화문"}) {
      var request = new Request(UUID.randomUUID(), 1, area, Set.of(Need.PARKING), false);
      assertThat(gateway.search(request, NOW.plusSeconds(30), () -> true).state())
          .isEqualTo(State.RESULTS);
    }
    var third = new Request(UUID.randomUUID(), 1, "서울숲", Set.of(Need.PARKING), false);
    assertThat(gateway.search(third, NOW.plusSeconds(30), () -> true).failureReason())
        .isEqualTo(FailureReason.BUDGET_EXHAUSTED);
    assertThat(gateway.consumedCalls()).isEqualTo(2);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void cancelledAndExpiredRequestsDoNotSpendBudgetAndConcurrentCallsStayWithinCap()
      throws Exception {
    var calls = new AtomicInteger();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) -> {
              calls.incrementAndGet();
              return new GptPlaceSourceDiscovery.Response(
                  200, MockWebRecommendationGateway.fictionalResponse());
            },
            () -> "fixture-key");
    var gateway = new RuntimeWebRecommendationGateway(clock, adapter, "gpt-6-luna", 3072, 3);
    var request = new Request(UUID.randomUUID(), 1, "서울숲", Set.of(Need.PARKING), false);
    assertThat(gateway.search(request, NOW.plusSeconds(30), () -> false).state())
        .isEqualTo(State.CANCELLED);
    assertThat(gateway.search(request, NOW, () -> true).state()).isEqualTo(State.EXPIRED);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<Result>>();
      for (int i = 0; i < 8; i++) {
        var distinct = new Request(UUID.randomUUID(), 1, "서울숲", Set.of(Need.PARKING), false);
        futures.add(pool.submit(() -> gateway.search(distinct, NOW.plusSeconds(30), () -> true)));
      }
      int results = 0, capped = 0;
      for (var future : futures) {
        var value = future.get();
        if (value.state() == State.RESULTS) results++;
        if (value.failureReason() == FailureReason.BUDGET_EXHAUSTED) capped++;
      }
      assertThat(results).isEqualTo(3);
      assertThat(capped).isEqualTo(5);
    }
    assertThat(gateway.consumedCalls()).isEqualTo(3);
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void rejectsMissingBudgetOrModel() {
    var adapter = new GptWebRecommendationAdapter(clock, (body, key, timeout) -> null, () -> "x");
    assertThatThrownBy(
            () -> new RuntimeWebRecommendationGateway(clock, adapter, "gpt-6-luna", 3072, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuntimeWebRecommendationGateway(clock, adapter, "", 3072, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
