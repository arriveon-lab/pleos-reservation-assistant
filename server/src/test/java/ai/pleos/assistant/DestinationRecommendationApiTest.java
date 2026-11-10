package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
      "app.destination-recommendations.catalog-path=src/test/resources/places/seoul-catalog-fictional.json",
      "spring.datasource.url=jdbc:h2:mem:destination-recommendations;DB_CLOSE_DELAY=-1"
    })
@AutoConfigureMockMvc
class DestinationRecommendationApiTest {
  private static final String BASE = "/api/experimental/destination-recommendations";
  @Autowired MockMvc http;
  @Autowired AuthService auth;
  @Autowired ObjectMapper json;

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    Clock destinationTestClock() {
      return Clock.fixed(Instant.parse("2026-11-10T00:01:00Z"), ZoneOffset.UTC);
    }
  }

  @Test
  void ownerPollsThreeFictionalCandidatesWithMandatoryParkingAndCannotReplayClosedGeneration()
      throws Exception {
    String first = auth.issue().accessToken();
    String other = auth.issue().accessToken();
    UUID view = UUID.randomUUID();
    var request =
        Map.of(
            "viewId",
            view,
            "generation",
            1,
            "userRequest",
            "도착지 근처 아기 의자 있는 맛집 추천해줘",
            "destination",
            Map.of("name", "가상 서울숲", "latitude", 37.544, "longitude", 127.055),
            "party",
            Map.of("adults", 2, "children", 1),
            "maxWaitMinutes",
            15,
            "requiredFeatures",
            Set.of("highChair"),
            "preferredCuisines",
            Set.of());
    http.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
        .andExpect(status().isUnauthorized());
    var missingWait = new java.util.HashMap<>(request);
    missingWait.remove("maxWaitMinutes");
    http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + first)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(missingWait)))
        .andExpect(status().isBadRequest());
    String started =
        http.perform(
                post(BASE)
                    .header("Authorization", "Bearer " + first)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(started).path("taskId").asText();
    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    JsonNode result = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      String body =
          http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + first))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      result = json.readTree(body);
      if (result.path("state").asText().equals("RESULTS")) break;
      Thread.sleep(10);
    }
    assertThat(result).isNotNull();
    assertThat(result.path("state").asText()).isEqualTo("RESULTS");
    assertThat(result.at("/result/listing/candidates").size()).isEqualTo(3);
    assertThat(result.at("/result/webState").asText()).isEqualTo("APPROVAL_REQUIRED");
    assertThat(result.at("/source/datasetId").asText()).isEqualTo("OA-18655");
    assertThat(result.at("/source/sourceSnapshotAt").isMissingNode()).isFalse();
    assertThat(result.at("/result/listing/requirements/requiredFeatures").toString())
        .contains("parking", "highChair");
    assertThat(result.at("/result/listing/candidates/0/bookable").asBoolean()).isFalse();
    assertThat(result.at("/result/listing/candidates/0/questions").toString())
        .contains("parking", "highChair");
    http.perform(delete(BASE + "/views/" + view).header("Authorization", "Bearer " + first))
        .andExpect(status().isOk());
    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + first))
        .andExpect(status().isOk());
    assertThat(
            json.readTree(
                    http.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + first))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("state")
                .asText())
        .isEqualTo("CANCELLED");
    http.perform(
            post(BASE)
                .header("Authorization", "Bearer " + first)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
        .andExpect(status().isBadRequest());
  }
}
