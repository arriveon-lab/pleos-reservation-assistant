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

/** Explicit one-shot operational permit. There is no automatic paid web access. */
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
      @Value("${app.destination-recommendations.web.approved-until:}") String until) {
    if (System.getenv("OPENAI_API_KEY") == null || System.getenv("OPENAI_API_KEY").isBlank())
      throw new IllegalStateException("Approved web credential required");
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
      var adapter =
          new GptWebRecommendationAdapter(clock, transport, () -> System.getenv("OPENAI_API_KEY"));
      return (actual, deadline, current) -> adapter.search(actual, permit, deadline, current);
    } catch (RuntimeException invalid) {
      // Do not echo approval values, input, or credentials into startup logs.
      throw new IllegalStateException("Invalid one-shot web approval configuration");
    }
  }
}
