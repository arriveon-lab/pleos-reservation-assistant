package ai.pleos.assistant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.web-recommendations.enabled", havingValue = "true")
@RequestMapping("/api/experimental/web-recommendations")
class WebRecommendationController {
  private final WebRecommendationService service;

  WebRecommendationController(WebRecommendationService service) {
    this.service = service;
  }

  @PostMapping
  WebRecommendation.Response start(
      HttpServletRequest http,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WebRecommendation.Request request) {
    return service.start(RequestActor.from(http), key, request);
  }

  @GetMapping("/{id}")
  WebRecommendation.Saved get(HttpServletRequest http, @PathVariable String id) {
    return service.get(RequestActor.from(http), id);
  }

  @PutMapping("/{id}/feedback")
  WebRecommendation.Saved feedback(
      HttpServletRequest http,
      @PathVariable String id,
      @RequestBody WebRecommendation.Feedback feedback) {
    return service.feedback(RequestActor.from(http), id, feedback);
  }

  @PostMapping("/{id}/close")
  Map<String, String> close(HttpServletRequest http, @PathVariable String id) {
    service.close(RequestActor.from(http), id);
    return Map.of("state", "CLOSED", "mode", "MOCK");
  }

  @DeleteMapping("/{id}")
  WebRecommendation.Saved delete(HttpServletRequest http, @PathVariable String id) {
    return service.delete(RequestActor.from(http), id);
  }
}
