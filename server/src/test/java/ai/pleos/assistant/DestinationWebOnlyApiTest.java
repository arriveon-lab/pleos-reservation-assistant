package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.destination-recommendations.enabled=true",
      "app.destination-recommendations.source=WEB_SEARCH",
      "spring.datasource.url=jdbc:h2:mem:destination-web-only;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
class DestinationWebOnlyApiTest {
  private static final String BASE = "/api/experimental/destination-recommendations";
  @Autowired MockMvc http;
  @Autowired AuthService auth;
  @Autowired ObjectMapper json;

  @TestConfiguration
  static class FictionalWeb {
    @Bean
    @Primary
    Clock webOnlyClock() {
      return Clock.fixed(Instant.parse("2026-11-11T00:01:00Z"), ZoneOffset.UTC);
    }

    @Bean
    DestinationRecommendationService.WebGateway fictionalDestinationWeb(Clock clock) {
      return (request, deadline, current) ->
          new WebRecommendationParser()
              .parse(
                  MockWebRecommendationGateway.fictionalResponse(),
                  request,
                  WebRecommendation.Mode.WEB_SEARCH,
                  clock.instant(),
                  clock.instant().plusSeconds(300));
    }
  }

  @Test
  void pollsThreeCitedWebPlacesWithoutKakaoOrCatalog() throws Exception {
    String token = auth.issue().accessToken();
    var request =
        Map.of(
            "viewId", UUID.randomUUID(),
            "generation", 1,
            "userRequest", "도착지 근처 아기의자 있는 맛집 추천해줘",
            "destination", Map.of("name", "서울숲", "latitude", 37.544, "longitude", 127.055),
            "party", Map.of("adults", 2, "children", 1),
            "maxWaitMinutes", 15,
            "requiredFeatures", Set.of("highChair"),
            "preferredCuisines", Set.of());
    var started =
        json.readTree(
            http.perform(
                    post(BASE)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var id = started.path("taskId").asText();
    com.fasterxml.jackson.databind.JsonNode result = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      result =
          json.readTree(
              http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + token))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      if (result.path("state").asText().equals("RESULTS")) break;
      Thread.sleep(10);
    }
    assertThat(result).isNotNull();
    assertThat(result.at("/source/provider").asText()).isEqualTo("WEB_SEARCH");
    assertThat(result.at("/result/listing").isNull()).isTrue();
    assertThat(result.at("/result/webRecommendations").size()).isEqualTo(3);
    assertThat(result.at("/result/webRecommendations/0/reviews").size()).isEqualTo(1);
    assertThat(result.at("/result/webRecommendations/0/bookable").asBoolean()).isFalse();
  }
}
