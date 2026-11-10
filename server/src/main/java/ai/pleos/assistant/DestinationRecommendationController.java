package ai.pleos.assistant;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Opt-in destination shortlist backed only by an explicitly supplied local public catalog. */
@RestController
@ConditionalOnProperty(name = "app.destination-recommendations.enabled", havingValue = "true")
@RequestMapping("/api/experimental/destination-recommendations")
class DestinationRecommendationController {
  record StartRequest(
      UUID viewId,
      long generation,
      Model.Destination destination,
      Model.Party party,
      int maxWaitMinutes,
      Set<String> requiredFeatures,
      Set<String> preferredCuisines) {}

  record Started(UUID taskId, RecommendationJobRegistry.State state) {}

  record Source(
      String datasetId,
      String catalogUrl,
      String licenseUrl,
      String attribution,
      Instant acquiredAt,
      Instant sourceSnapshotAt) {}

  record Polled(
      UUID id,
      RecommendationJobRegistry.State state,
      PlaceRecommendationPlanner.Plan result,
      Instant validUntil,
      Source source) {}

  private final PublicDestinationRecommendationService service;

  DestinationRecommendationController(PublicDestinationRecommendationService service) {
    this.service = service;
  }

  @PostMapping
  Started start(HttpServletRequest http, @RequestBody StartRequest input) {
    RequestActor actor = RequestActor.from(http);
    try {
      if (input == null
          || input.viewId() == null
          || input.destination() == null
          || input.destination().name() == null
          || input.destination().name().isBlank()
          || input.destination().latitude() == null
          || input.destination().longitude() == null
          || input.requiredFeatures() == null
          || input.preferredCuisines() == null) {
        throw new IllegalArgumentException("Missing destination");
      }
      var features = new HashSet<>(input.requiredFeatures());
      // Parking is an investigation requirement for every product recommendation.
      features.add("parking");
      var requirements =
          new PlaceRecommendationPlanner.Requirements(
              input.party(), input.maxWaitMinutes(), features, input.preferredCuisines());
      var query = PlaceQuery.forDestination(input.destination());
      UUID id =
          service.start(actor.subject(), input.viewId(), input.generation(), query, requirements);
      return new Started(id, service.poll(actor.subject(), id).orElseThrow().state());
    } catch (IllegalArgumentException error) {
      throw ApiError.bad("INVALID_DESTINATION_RECOMMENDATION", "추천 요청을 확인해 주세요.");
    } catch (IllegalStateException error) {
      throw new ApiError(
          HttpStatus.TOO_MANY_REQUESTS, "RECOMMENDATION_CAPACITY", "잠시 후 다시 시도해 주세요.");
    }
  }

  @GetMapping("/{id}")
  Polled poll(HttpServletRequest http, @PathVariable UUID id) {
    var snapshot =
        service
            .poll(RequestActor.from(http).subject(), id)
            .orElseThrow(
                () ->
                    new ApiError(HttpStatus.NOT_FOUND, "RECOMMENDATION_NOT_FOUND", "추천 작업이 없습니다."));
    var catalog = service.provenance();
    return new Polled(
        snapshot.id(),
        snapshot.state(),
        snapshot.result(),
        snapshot.validUntil(),
        new Source(
            catalog.datasetId(),
            catalog.catalogUrl(),
            catalog.licenseUrl(),
            catalog.attribution(),
            catalog.acquiredAt(),
            catalog.sourceSnapshotAt()));
  }

  @DeleteMapping("/views/{viewId}")
  void invalidate(HttpServletRequest http, @PathVariable UUID viewId) {
    service.invalidate(RequestActor.from(http).subject(), viewId);
  }
}
