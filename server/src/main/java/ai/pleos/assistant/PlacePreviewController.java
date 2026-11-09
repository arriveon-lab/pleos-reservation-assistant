package ai.pleos.assistant;

import static ai.pleos.assistant.PlacePreviewContract.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Experimental HTTP contract only; default-off, MOCK-only, never calls Store/WorkflowService. */
@RestController
@RequestMapping("/api/experimental/place-previews")
@ConditionalOnProperty(name = "app.place-preview.enabled", havingValue = "true")
public class PlacePreviewController {
  private final PlacePreviewRegistry registry;
  private final Clock clock;
  private final PlaceRecommendationPlanner planner;

  public PlacePreviewController(PlacePreviewRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
    this.planner = new PlaceRecommendationPlanner(clock);
  }

  @PostMapping
  public Session open(Principal principal, @RequestBody CreateRequest body) {
    return new Session(registry.open(owner(principal)), "MOCK");
  }

  @PostMapping("/{id}/queries")
  public CompletionStage<Result> query(
      Principal principal, @PathVariable UUID id, @Valid @RequestBody QueryRequest body) {
    var requirements = body.requirements() == null ? null : body.requirements().internal();
    var query = body.query().internal();
    return registry
        .start(owner(principal), id, body.generation(), query, requirements)
        .thenApply(outcome -> result(id, query, requirements, outcome));
  }

  @PostMapping("/{id}/cancel")
  public Cancelled cancel(
      Principal principal, @PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
    boolean cancelled = registry.cancel(owner(principal), id, body.generation());
    return new Cancelled(id, body.generation(), cancelled, "CANCEL_REQUESTED", "MOCK");
  }

  @PostMapping("/{id}/close")
  public Closed close(Principal principal, @PathVariable UUID id, @RequestBody CreateRequest body) {
    registry.close(owner(principal), id);
    return new Closed(id, "CLOSED", "MOCK");
  }

  private static PlacePreviewRegistry.Owner owner(Principal principal) {
    if (principal == null)
      throw new ApiError(
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          "PREVIEW_AUTHENTICATION_REQUIRED",
          "검증된 사용자 인증이 필요합니다.");
    return new PlacePreviewRegistry.Owner(principal.getName());
  }

  private Result result(
      UUID id,
      PlaceQuery query,
      PlaceRecommendationPlanner.Requirements requirements,
      PlacePreviewSession.Outcome outcome) {
    if (outcome.status() != PlacePreviewSession.Status.RETURNED)
      return response(id, outcome, outcome.status().name(), List.of(), null, null, null, 0);
    if (outcome.result() instanceof PlaceSearchResult.Failure failure)
      return response(id, outcome, "FAILED", List.of(), null, null, failure.kind().name(), 0);
    var success = (PlaceSearchResult.Success) outcome.result();
    if (success.places().size() > query.limit()
        || success.fetchedAt().isAfter(clock.instant())
        || (success.places().isEmpty() && success.rejectedRecords() > 0)
        || success.places().stream().anyMatch(p -> p.provider() != PlaceSummary.Provider.MOCK))
      return response(id, outcome, "FAILED", List.of(), null, null, "INVALID_RESPONSE", 0);
    var expiresAt = success.fetchedAt().plus(Duration.ofMinutes(5));
    if (!expiresAt.isAfter(clock.instant()))
      return response(id, outcome, "FAILED", List.of(), null, null, "INVALID_RESPONSE", 0);
    if (requirements != null) {
      var plan = planner.evaluate(PlaceSummary.Provider.MOCK, query, success, requirements);
      if (plan.state() == PlaceRecommendationPlanner.State.FAILED)
        return response(id, outcome, "FAILED", List.of(), null, null, plan.failure().name(), 0);
      var places =
          plan.candidates().stream()
              .map(
                  p ->
                      new Place(
                          p.name(),
                          p.cuisine(),
                          p.address(),
                          p.straightLineDistanceMeters(),
                          "UNKNOWN",
                          "UNKNOWN",
                          "UNKNOWN",
                          false))
              .toList();
      return new Result(
          id,
          outcome.generation(),
          plan.state().name(),
          "MOCK",
          "MOCK",
          places,
          plan.listingFetchedAt(),
          plan.displayValidUntil(),
          null,
          plan.rejectedRecords(),
          "MOCK",
          plan);
    }
    List<Place> places =
        success.places().stream()
            .map(
                p ->
                    new Place(
                        p.name(),
                        p.cuisine(),
                        p.address(),
                        p.distanceMeters(),
                        "UNKNOWN",
                        "UNKNOWN",
                        "UNKNOWN",
                        false))
            .toList();
    return response(
        id,
        outcome,
        places.isEmpty() ? "EMPTY" : "RESULTS",
        places,
        success.fetchedAt(),
        expiresAt,
        null,
        success.rejectedRecords());
  }

  private static Result response(
      UUID id,
      PlacePreviewSession.Outcome outcome,
      String state,
      List<Place> places,
      java.time.Instant fetchedAt,
      java.time.Instant expiresAt,
      String failure,
      int rejectedRecords) {
    return new Result(
        id,
        outcome.generation(),
        state,
        "MOCK",
        "MOCK",
        places,
        fetchedAt,
        expiresAt,
        failure,
        rejectedRecords,
        "MOCK",
        null);
  }

  @ExceptionHandler(PlacePreviewRegistry.AccessRejected.class)
  public ResponseEntity<Map<String, String>> rejected(PlacePreviewRegistry.AccessRejected error) {
    return switch (error.reason()) {
      case NOT_FOUND -> error(404, "PREVIEW_NOT_FOUND", "미리보기를 찾을 수 없습니다.");
      case SESSION_LIMIT -> error(429, "PREVIEW_SESSION_LIMIT", "미리보기 세션 수가 제한되었습니다.");
      case CLOSED -> error(503, "PREVIEW_UNAVAILABLE", "미리보기를 사용할 수 없습니다.");
    };
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<Map<String, String>> invalid(Exception error) {
    return error(400, "INVALID_REQUEST", "미리보기 요청 필드를 확인하세요.");
  }

  private static ResponseEntity<Map<String, String>> error(
      int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", message, "mode", "MOCK"));
  }
}
