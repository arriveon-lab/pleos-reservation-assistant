package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Operator-enabled paid search for authenticated app requests. The process-wide cap limits calls
 * after each startup; every request still uses a one-shot adapter permit and never retries.
 */
final class RuntimeWebRecommendationGateway implements DestinationRecommendationService.WebGateway {
  private final Clock clock;
  private final GptWebRecommendationAdapter adapter;
  private final String model;
  private final int maxOutputTokens;
  private final int maxCalls;
  private final AtomicInteger consumed = new AtomicInteger();

  RuntimeWebRecommendationGateway(
      Clock clock,
      GptWebRecommendationAdapter adapter,
      String model,
      int maxOutputTokens,
      int maxCalls) {
    this.clock = Objects.requireNonNull(clock);
    this.adapter = Objects.requireNonNull(adapter);
    if (model == null || !model.matches("[a-zA-Z0-9._:-]{1,100}"))
      throw new IllegalArgumentException("Explicit runtime web model required");
    if (maxOutputTokens < 256 || maxOutputTokens > 3072 || maxCalls < 1 || maxCalls > 1000)
      throw new IllegalArgumentException("Invalid runtime web budget");
    this.model = model;
    this.maxOutputTokens = maxOutputTokens;
    this.maxCalls = maxCalls;
  }

  @Override
  public Result search(Request request, Instant deadline, BooleanSupplier current) {
    return search(request, deadline, current, null);
  }

  Result search(Request request, Instant deadline, BooleanSupplier current, Result nearby) {
    return search(request, deadline, current, nearby, null);
  }

  Result discover(
      Request request,
      Instant deadline,
      BooleanSupplier current,
      Function<JsonNode, Set<String>> verifyBranches) {
    return search(request, deadline, current, null, verifyBranches);
  }

  private Result search(
      Request request,
      Instant deadline,
      BooleanSupplier current,
      Result nearby,
      Function<JsonNode, Set<String>> verifyBranches) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(deadline);
    Objects.requireNonNull(current);
    if (!current.getAsBoolean()) return empty(Mode.WEB_SEARCH, State.CANCELLED);
    Instant now = clock.instant();
    if (!now.isBefore(deadline)) return empty(Mode.WEB_SEARCH, State.EXPIRED);
    while (true) {
      int used = consumed.get();
      if (used >= maxCalls) return failed(Mode.WEB_SEARCH, FailureReason.BUDGET_EXHAUSTED);
      if (consumed.compareAndSet(used, used + 1)) break;
    }
    var permit =
        new GptWebRecommendationAdapter.Permit(
            request, model, now.plusSeconds(31), maxOutputTokens);
    return adapter.search(request, permit, deadline, current, nearby, verifyBranches);
  }

  int consumedCalls() {
    return consumed.get();
  }
}
