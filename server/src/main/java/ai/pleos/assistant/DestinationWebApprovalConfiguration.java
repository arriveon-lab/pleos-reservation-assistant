package ai.pleos.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit one-shot test permit or operator-enabled, capped app runtime access. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "app.destination-recommendations.enabled",
      "app.destination-recommendations.web.enabled"
    },
    havingValue = "true")
class DestinationWebApprovalConfiguration {
  @Bean(destroyMethod = "close")
  GptWebSearchHttpTransport destinationGptTransport() {
    return new GptWebSearchHttpTransport();
  }

  @Bean
  DestinationRecommendationService.WebGateway destinationWebGateway(
      Clock clock,
      GptWebSearchHttpTransport transport,
      @Value("${app.destination-recommendations.web.approved-view-id:}") String view,
      @Value("${app.destination-recommendations.web.approved-generation:0}") long generation,
      @Value("${app.destination-recommendations.web.approved-area:}") String area,
      @Value("${app.destination-recommendations.web.approved-needs:}") String needs,
      @Value("${app.destination-recommendations.web.approved-model:}") String model,
      @Value("${app.destination-recommendations.web.approved-max-output-tokens:0}") int outputLimit,
      @Value("${app.destination-recommendations.web.approved-until:}") String until,
      @Value("${app.destination-recommendations.web.runtime-enabled:false}") boolean runtimeEnabled,
      @Value("${app.destination-recommendations.web.gumi-catalog-enabled:false}")
          boolean gumiCatalogEnabled,
      @Value("${app.destination-recommendations.web.runtime-model:}") String runtimeModel,
      @Value("${app.destination-recommendations.web.runtime-max-output-tokens:0}")
          int runtimeOutputLimit,
      @Value("${app.destination-recommendations.web.runtime-max-calls-per-startup:0}")
          int runtimeMaxCalls) {
    if (System.getenv("OPENAI_API_KEY") == null || System.getenv("OPENAI_API_KEY").isBlank())
      throw new IllegalStateException("Approved web credential required");
    var adapter =
        new GptWebRecommendationAdapter(clock, transport, () -> System.getenv("OPENAI_API_KEY"));
    if (runtimeEnabled) {
      if (!view.isBlank()
          || !area.isBlank()
          || !needs.isBlank()
          || !model.isBlank()
          || !until.isBlank()
          || generation != 0
          || outputLimit != 0) throw new IllegalStateException("Conflicting web access modes");
      var web =
          new RuntimeWebRecommendationGateway(
              clock, adapter, runtimeModel, runtimeOutputLimit, runtimeMaxCalls);
      if (!gumiCatalogEnabled) return web;
      return (request, deadline, current) -> {
        if (current.getAsBoolean() && clock.instant().isBefore(deadline)) {
          var catalog = GumiRestaurantCatalog.search(request, clock);
          if (catalog.isPresent()) return catalog.orElseThrow();
        }
        return web.search(request, deadline, current);
      };
    }
    if (needs.isBlank()) throw new IllegalStateException("Approved web needs required");
    var parsed = EnumSet.noneOf(WebRecommendation.Need.class);
    try {
      for (String value : needs.split(",", -1))
        parsed.add(WebRecommendation.Need.valueOf(value.strip()));
      if (!parsed.contains(WebRecommendation.Need.PARKING))
        throw new IllegalArgumentException("Parking approval required");
      var request =
          new WebRecommendation.Request(
              UUID.fromString(view), generation, area, Set.copyOf(parsed), false);
      var permit =
          new GptWebRecommendationAdapter.Permit(request, model, Instant.parse(until), outputLimit);
      return (actual, deadline, current) -> adapter.search(actual, permit, deadline, current);
    } catch (RuntimeException invalid) {
      // Do not echo approval values, input, or credentials into startup logs.
      throw new IllegalStateException("Invalid one-shot web approval configuration");
    }
  }
}
