package ai.pleos.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
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

/** Opt-in destination shortlist using an explicitly selected Kakao or public source. */
@RestController
@ConditionalOnProperty(name = "app.destination-recommendations.enabled", havingValue = "true")
@RequestMapping("/api/experimental/destination-recommendations")
class DestinationRecommendationController {
  record StartRequest(
      UUID viewId,
      @JsonProperty(required = true) long generation,
      String userRequest,
      Model.Destination destination,
      Model.Party party,
      @JsonProperty(required = true) int maxWaitMinutes,
      Set<String> requiredFeatures,
      Set<String> preferredCuisines) {}

  record Started(UUID taskId, RecommendationJobRegistry.State state) {}

  record WebStartRequest(
      UUID viewId,
      @JsonProperty(required = true) long generation,
      String destination,
      Set<WebRecommendation.Need> requiredNeeds,
      WebRecommendation.VenueCategory venueCategory,
      String foodPreferenceText) {}

  record Polled(
      UUID id,
      RecommendationJobRegistry.State state,
      DestinationRecommendationService.Recommendation result,
      java.time.Instant validUntil,
      DestinationRecommendationService.SourceInfo source) {}

  private final DestinationRecommendationService service;

  DestinationRecommendationController(DestinationRecommendationService service) {
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
          || input.destination().name().length() > 100
          || input.destination().name().codePoints().anyMatch(Character::isISOControl)
          || input.destination().latitude() == null
          || input.destination().longitude() == null
          || input.requiredFeatures() == null
          || input.preferredCuisines() == null) {
        throw new IllegalArgumentException("Missing destination");
      }
      var features =
          UserRecommendationIntent.requiredFeatures(input.userRequest(), input.requiredFeatures());
      var requirements =
          new PlaceRecommendationPlanner.Requirements(
              input.party(),
              input.maxWaitMinutes(),
              features,
              UserRecommendationIntent.preferredCuisines(
                  input.userRequest(), input.preferredCuisines(), false));
      var query = PlaceQuery.forDestination(input.destination());
      UUID id =
          service.start(
              actor.subject(),
              input.viewId(),
              input.generation(),
              input.destination().name(),
              query,
              requirements);
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
    return new Polled(
        snapshot.id(),
        snapshot.state(),
        snapshot.result(),
        snapshot.validUntil(),
        service.source());
  }

  @PostMapping("/web")
  Started startWeb(HttpServletRequest http, @RequestBody WebStartRequest input) {
    var actor = RequestActor.from(http);
    if (service.source().provider() != PlaceSummary.Provider.WEB_SEARCH) {
      throw new ApiError(HttpStatus.NOT_FOUND, "WEB_SEARCH_DISABLED", "웹검색 추천을 사용할 수 없어요.");
    }
    try {
      var needs = java.util.EnumSet.of(WebRecommendation.Need.PARKING);
      if (input.requiredNeeds() == null
          || input.requiredNeeds().stream().anyMatch(java.util.Objects::isNull))
        throw new IllegalArgumentException("Invalid required needs");
      needs.addAll(input.requiredNeeds());
      Set<String> cuisines;
      try {
        cuisines =
            UserRecommendationIntent.preferredCuisines(input.foodPreferenceText(), Set.of(), true);
      } catch (IllegalArgumentException unclearPreference) {
        throw ApiError.bad(
            "FOOD_PREFERENCE_UNCLEAR",
            "먹고 싶은 음식 종류를 한식·양식·일식·중식·분식·아시아음식 중에서 알려 주세요. 예: 양식 먹고 싶어, 양식 말고 한식");
      }
      var request =
          new WebRecommendation.Request(
              input.viewId(),
              input.generation(),
              input.destination(),
              needs,
              false,
              input.venueCategory(),
              cuisines);
      UUID id = service.startWeb(actor.subject(), request);
      return new Started(id, service.poll(actor.subject(), id).orElseThrow().state());
    } catch (IllegalArgumentException error) {
      throw ApiError.bad("INVALID_DESTINATION_RECOMMENDATION", "목적지와 필수 조건을 확인해 주세요.");
    } catch (IllegalStateException error) {
      throw new ApiError(
          HttpStatus.TOO_MANY_REQUESTS, "RECOMMENDATION_CAPACITY", "잠시 후 다시 시도해 주세요.");
    }
  }

  @DeleteMapping("/views/{viewId}")
  void invalidate(HttpServletRequest http, @PathVariable UUID viewId) {
    service.invalidate(RequestActor.from(http).subject(), viewId);
  }

  @PostMapping("/views/{viewId}/close")
  java.util.Map<String, String> closeView(HttpServletRequest http, @PathVariable UUID viewId) {
    invalidate(http, viewId);
    return java.util.Map.of("state", "CLOSED");
  }
}
