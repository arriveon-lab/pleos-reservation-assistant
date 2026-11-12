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
      return (request, deadline, current) -> {
        String response = MockWebRecommendationGateway.fictionalResponse();
        if ("두 곳 시험".equals(request.area())
            || "업종 시험".equals(request.area())
            || "음식 취향 시험".equals(request.area())) {
          try {
            var root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                    new ObjectMapper().readTree(response);
            var body =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                    new ObjectMapper().readTree(root.at("/output/1/content/0/text").asText());
            var first = (com.fasterxml.jackson.databind.node.ObjectNode) body.at("/candidates/0");
            var second = (com.fasterxml.jackson.databind.node.ObjectNode) body.at("/candidates/1");
            if ("음식 취향 시험".equals(request.area())) {
              first.put("venueCategory", "RESTAURANT").put("cuisine", "양식");
              first
                  .putArray("businessDetails")
                  .addObject()
                  .put("kind", "CUISINE")
                  .put("condition", "양식: 가상 파스타 메뉴")
                  .put("sourceUrl", first.path("identitySourceUrl").asText())
                  .put("sourceType", "MERCHANT_PAGE")
                  .putNull("sourceUpdatedAt");
            } else if ("업종 시험".equals(request.area())) {
              first.put("venueCategory", "CAFE");
              second.put("venueCategory", "RESTAURANT");
            } else {
              first.put("address", "UNKNOWN");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/output/1/content/0"))
                .put("text", body.toString());
            response = root.toString();
          } catch (Exception invalidFixture) {
            throw new IllegalStateException(invalidFixture);
          }
        }
        return new WebRecommendationParser()
            .parse(
                response,
                request,
                WebRecommendation.Mode.WEB_SEARCH,
                clock.instant(),
                clock.instant().plusSeconds(300));
      };
    }
  }

  @Test
  void foodPhraseSurvivesAuthenticatedSearchAndKeepsRequiredNeeds() throws Exception {
    String token = auth.issue().accessToken();
    var request =
        new java.util.HashMap<String, Object>(
            Map.of(
                "viewId",
                UUID.randomUUID(),
                "generation",
                1,
                "destination",
                "음식 취향 시험",
                "requiredNeeds",
                Set.of("HIGH_CHAIR", "STEP_FREE_ACCESS"),
                "venueCategory",
                "RESTAURANT",
                "foodPreferenceText",
                "양식 먹고 싶어"));
    var started =
        json.readTree(
            http.perform(
                    post(BASE + "/web")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    com.fasterxml.jackson.databind.JsonNode result = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      result =
          json.readTree(
              http.perform(
                      get(BASE + "/" + started.path("taskId").asText())
                          .header("Authorization", "Bearer " + token))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      if (result.path("state").asText().equals("RESULTS")) break;
      Thread.sleep(10);
    }
    assertThat(result.at("/result/preferredCuisines/0").asText()).isEqualTo("양식");
    assertThat(result.at("/result/webRecommendations").size()).isEqualTo(1);
    assertThat(result.at("/result/webRecommendations/0/assessments").findValuesAsText("need"))
        .containsExactlyInAnyOrder("PARKING", "HIGH_CHAIR", "STEP_FREE_ACCESS");
    assertThat(result.at("/result/webRecommendations/0/businessDetails/CUISINE/condition").asText())
        .contains("양식");
    request.put("foodPreferenceText", "양식 말고");
    var failure =
        http.perform(
                post(BASE + "/web")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(failure).contains("FOOD_PREFERENCE_UNCLEAR");
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

  @Test
  void pollsTwoValidPlacesAsResultsWhenAnotherAddressIsUnknown() throws Exception {
    String token = auth.issue().accessToken();
    var request =
        Map.of(
            "viewId",
            UUID.randomUUID(),
            "generation",
            1,
            "destination",
            "두 곳 시험",
            "requiredNeeds",
            Set.of("HIGH_CHAIR"));
    var started =
        json.readTree(
            http.perform(
                    post(BASE + "/web")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    com.fasterxml.jackson.databind.JsonNode result = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      result =
          json.readTree(
              http.perform(
                      get(BASE + "/" + started.path("taskId").asText())
                          .header("Authorization", "Bearer " + token))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      if (result.path("state").asText().equals("RESULTS")) break;
      Thread.sleep(10);
    }
    assertThat(result).isNotNull();
    assertThat(result.at("/result/webState").asText()).isEqualTo("RESULTS");
    assertThat(result.at("/result/webRecommendations").size()).isEqualTo(2);
    var needs = result.at("/result/webRecommendations/0/assessments").findValuesAsText("need");
    assertThat(needs).containsExactlyInAnyOrder("PARKING", "HIGH_CHAIR");
  }

  @Test
  void categoryFilterDropsOtherAndUnknownCategoriesBeforeAssigningOrdinals() throws Exception {
    String token = auth.issue().accessToken();
    for (String category : java.util.List.of("RESTAURANT", "CAFE")) {
      var request =
          Map.of(
              "viewId",
              UUID.randomUUID(),
              "generation",
              1,
              "destination",
              "업종 시험",
              "requiredNeeds",
              Set.of("PARKING"),
              "venueCategory",
              category);
      var started =
          json.readTree(
              http.perform(
                      post(BASE + "/web")
                          .header("Authorization", "Bearer " + token)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(json.writeValueAsBytes(request)))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      com.fasterxml.jackson.databind.JsonNode result = null;
      for (int attempt = 0; attempt < 100; attempt++) {
        result =
            json.readTree(
                http.perform(
                        get(BASE + "/" + started.path("taskId").asText())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        if (result.path("state").asText().equals("RESULTS")) break;
        Thread.sleep(10);
      }
      assertThat(result).isNotNull();
      var candidates = result.at("/result/webRecommendations");
      assertThat(candidates.size()).isEqualTo(1);
      assertThat(candidates.at("/0/venueCategory").asText()).isEqualTo(category);
      assertThat(candidates.at("/0/ordinal").asInt()).isEqualTo(1);
    }
  }
}
