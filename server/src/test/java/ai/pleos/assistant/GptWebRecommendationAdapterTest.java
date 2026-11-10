package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GptWebRecommendationAdapterTest {
  static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Request request =
      new Request(UUID.randomUUID(), 1, "가상 서울숲", Set.of(Need.PET_FRIENDLY), true);

  @Test
  void noPermitNoCurrentOrExpiredNeverReadsKeyOrCallsTransport() {
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (b, k, t) -> {
              throw new AssertionError();
            },
            () -> {
              throw new AssertionError();
            });
    assertThat(adapter.search(request, null, NOW.plusSeconds(30), () -> true).state())
        .isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(adapter.search(request, null, NOW.plusSeconds(30), () -> false).state())
        .isEqualTo(State.CANCELLED);
    assertThat(adapter.search(request, null, NOW, () -> true).state()).isEqualTo(State.EXPIRED);
  }

  @Test
  void boundedRequestOmitsPrivateContextAndCitationsFeedParser() throws Exception {
    var json = new ObjectMapper();
    var calls = new AtomicInteger();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) -> {
              calls.incrementAndGet();
              var data = json.readTree(body);
              assertThat(data.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
              assertThat(data.path("tool_choice").asText()).isEqualTo("required");
              assertThat(data.path("store").asBoolean()).isFalse();
              assertThat(data.path("max_tool_calls").asInt()).isEqualTo(1);
              assertThat(data.at("/text/format/type").asText()).isEqualTo("json_schema");
              assertThat(data.at("/text/format/strict").asBoolean()).isTrue();
              assertThat(
                      data.at(
                              "/text/format/schema/properties/candidates/items/properties/reviews/type")
                          .asText())
                  .isEqualTo("array");
              assertThat(data.path("max_output_tokens").asInt()).isEqualTo(3072);
              assertThat(data.at("/reasoning/effort").asText()).isEqualTo("low");
              assertThat(body.length()).isLessThanOrEqualTo(8192);
              assertThat(data.path("instructions").asText()).contains("도로명주소");
              assertThat(data.path("instructions").asText()).contains("about 1 km");
              assertThat(body)
                  .doesNotContain(
                      request.viewId().toString(), "personalizationConsent", "fixture-key");
              assertThat(timeout.toSeconds()).isLessThanOrEqualTo(30);
              return new GptPlaceSourceDiscovery.Response(
                  200, MockWebRecommendationGateway.fictionalResponse());
            },
            () -> "fixture-key");
    var permit =
        new GptWebRecommendationAdapter.Permit(request, "explicit-test-model", NOW.plusSeconds(30));
    var result = adapter.search(request, permit, NOW.plusSeconds(60), () -> true);
    assertThat(result.state()).isEqualTo(State.RESULTS);
    assertThat(result.candidates().getFirst().reviews()).hasSize(1);
    assertThat(adapter.search(request, permit, NOW.plusSeconds(60), () -> true).state())
        .isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void failedProviderReportsOnlySafeReasonAndConsumesPermit() {
    var calls = new AtomicInteger();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) -> {
              calls.incrementAndGet();
              return new GptPlaceSourceDiscovery.Response(429, null);
            },
            () -> "fixture-key");
    var permit =
        new GptWebRecommendationAdapter.Permit(request, "explicit-test-model", NOW.plusSeconds(30));
    assertThat(adapter.search(request, permit, NOW.plusSeconds(30), () -> true).failureReason())
        .isEqualTo(FailureReason.HTTP_RATE_LIMITED);
    assertThat(adapter.search(request, permit, NOW.plusSeconds(30), () -> true).state())
        .isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(calls.get()).isEqualTo(1);

    var malformed =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) -> new GptPlaceSourceDiscovery.Response(200, "{}"),
            () -> "fixture-key");
    assertThat(
            malformed
                .search(
                    request,
                    new GptWebRecommendationAdapter.Permit(
                        request, "explicit-test-model", NOW.plusSeconds(30)),
                    NOW.plusSeconds(30),
                    () -> true)
                .failureReason())
        .isEqualTo(FailureReason.MISSING_WEB_SEARCH);

    var incomplete =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) ->
                new GptPlaceSourceDiscovery.Response(200, "{\"status\":\"incomplete\"}"),
            () -> "fixture-key");
    assertThat(
            incomplete
                .search(
                    request,
                    new GptWebRecommendationAdapter.Permit(
                        request, "explicit-test-model", NOW.plusSeconds(30)),
                    NOW.plusSeconds(30),
                    () -> true)
                .failureReason())
        .isEqualTo(FailureReason.INCOMPLETE_RESPONSE);

    var noText =
        new GptWebRecommendationAdapter(
            clock,
            (body, key, timeout) ->
                new GptPlaceSourceDiscovery.Response(
                    200, "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\"}]}"),
            () -> "fixture-key");
    assertThat(
            noText
                .search(
                    request,
                    new GptWebRecommendationAdapter.Permit(
                        request, "explicit-test-model", NOW.plusSeconds(30)),
                    NOW.plusSeconds(30),
                    () -> true)
                .failureReason())
        .isEqualTo(FailureReason.MISSING_OUTPUT_TEXT);
  }

  @Test
  void lowerCostPermitLimitsOutputTokensWithoutChangingDefault() throws Exception {
    var adapter = new GptWebRecommendationAdapter(clock, (b, k, t) -> null, () -> "fixture-key");
    var limited =
        new GptWebRecommendationAdapter.Permit(request, "gpt-6-luna", NOW.plusSeconds(30), 1024);
    var json = new ObjectMapper();
    assertThat(
            json.readTree(adapter.requestJson(request, limited.model, limited.maxOutputTokens))
                .path("max_output_tokens")
                .asInt())
        .isEqualTo(1024);
    assertThat(limited.maxOutputTokens).isLessThan(3072);
  }

  @Test
  void concurrentPermitAndFailedAttemptNeverAutomaticallyRetry() throws Exception {
    var calls = new AtomicInteger();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (b, k, t) -> {
              calls.incrementAndGet();
              return new GptPlaceSourceDiscovery.Response(503, null);
            },
            () -> "fixture-key");
    var permit =
        new GptWebRecommendationAdapter.Permit(request, "explicit-test-model", NOW.plusSeconds(30));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> adapter.search(request, permit, NOW.plusSeconds(30), () -> true));
      var b = pool.submit(() -> adapter.search(request, permit, NOW.plusSeconds(30), () -> true));
      assertThat(Set.of(a.get().state(), b.get().state()))
          .containsExactlyInAnyOrder(State.FAILED, State.APPROVAL_REQUIRED);
    }
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void lateSuccessIsHiddenAfterCancellationAndInterruptedFlagIsPreserved() {
    var current = new java.util.concurrent.atomic.AtomicBoolean(true);
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (b, k, t) -> {
              current.set(false);
              return new GptPlaceSourceDiscovery.Response(
                  200, MockWebRecommendationGateway.fictionalResponse());
            },
            () -> "fixture-key");
    var permit =
        new GptWebRecommendationAdapter.Permit(request, "explicit-test-model", NOW.plusSeconds(30));
    assertThat(adapter.search(request, permit, NOW.plusSeconds(30), current::get).state())
        .isEqualTo(State.CANCELLED);
    adapter =
        new GptWebRecommendationAdapter(
            clock,
            (b, k, t) -> {
              throw new InterruptedException();
            },
            () -> "fixture-key");
    try {
      assertThat(
              adapter
                  .search(
                      request,
                      new GptWebRecommendationAdapter.Permit(
                          request, "explicit-test-model", NOW.plusSeconds(30)),
                      NOW.plusSeconds(30),
                      () -> true)
                  .state())
          .isEqualTo(State.CANCELLED);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
