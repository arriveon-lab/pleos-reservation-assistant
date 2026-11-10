package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No implicit fixture, API key, live search, GPT call or telephone dispatch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.destination-recommendations.enabled", havingValue = "true")
class DestinationRecommendationConfiguration {
  @Bean(value = "destinationRecommendationWorkers", destroyMethod = "shutdownNow")
  ExecutorService destinationRecommendationWorkers() {
    return Executors.newFixedThreadPool(2);
  }

  @Bean(destroyMethod = "close")
  PublicDestinationRecommendationService publicDestinationRecommendationService(
      ObjectMapper mapper,
      Clock clock,
      @Qualifier("destinationRecommendationWorkers") ExecutorService workers,
      @Value("${app.destination-recommendations.catalog-path:}") String catalogPath)
      throws IOException {
    if (catalogPath.isBlank()) throw new IllegalStateException("Public catalog path required");
    var catalog = SeoulPlaceCatalog.load(Path.of(catalogPath), mapper);
    return new PublicDestinationRecommendationService(
        new SeoulPlaceSearchAdapter(catalog, clock), clock, workers);
  }
}
