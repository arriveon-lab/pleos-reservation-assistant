package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicitly selected, memory-only place source with optional approved web research. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.destination-recommendations.enabled", havingValue = "true")
class DestinationRecommendationConfiguration {
  @Bean(value = "destinationRecommendationWorkers", destroyMethod = "shutdownNow")
  ExecutorService destinationRecommendationWorkers() {
    return Executors.newFixedThreadPool(2);
  }

  @Bean(destroyMethod = "close")
  DestinationRecommendationService destinationRecommendationService(
      ObjectMapper mapper,
      Clock clock,
      @Qualifier("destinationRecommendationWorkers") ExecutorService workers,
      ObjectProvider<DestinationRecommendationService.WebGateway> webProvider,
      @Value("${app.destination-recommendations.source:PUBLIC_CATALOG}") String source,
      @Value("${app.destination-recommendations.catalog-path:}") String catalogPath,
      @Value("${KAKAO_REST_API_KEY:}") String kakaoKey)
      throws IOException {
    var web = webProvider.getIfAvailable();
    if ("WEB_SEARCH".equals(source)) {
      if (web == null) throw new IllegalStateException("Approved web search required");
      return DestinationRecommendationService.webOnly(clock, workers, web);
    }
    if ("KAKAO".equals(source)) {
      if (kakaoKey.isBlank()) throw new IllegalStateException("Kakao key required");
      var adapter = new KakaoPlaceSearchAdapter(kakaoKey, new KakaoPlaceMapper(mapper, clock));
      return web == null
          ? new DestinationRecommendationService(adapter, clock, workers)
          : new DestinationRecommendationService(adapter, clock, workers, web);
    }
    if (!"PUBLIC_CATALOG".equals(source) || catalogPath.isBlank())
      throw new IllegalStateException("Explicit destination source required");
    var catalog = SeoulPlaceCatalog.load(Path.of(catalogPath), mapper);
    var adapter = new SeoulPlaceSearchAdapter(catalog, clock);
    return web == null
        ? new DestinationRecommendationService(adapter, clock, workers)
        : new DestinationRecommendationService(adapter, clock, workers, web);
  }
}
