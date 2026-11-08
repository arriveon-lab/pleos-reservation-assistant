package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:places;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PlaceSearchWorkflowTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  @Autowired WorkflowService service;
  @Autowired ControlledPlaces places;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;

  @TestConfiguration
  static class PlacesConfig {
    @Bean
    @Primary
    Clock placeClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    ControlledPlaces controlledPlaces(Clock clock) {
      return new ControlledPlaces(clock);
    }
  }

  static class ControlledPlaces extends MockPlaceSearchAdapter {
    List<PlaceSummary> listings = List.of();
    int calls;
    PlaceQuery lastQuery;

    ControlledPlaces(Clock clock) {
      super(clock);
    }

    @Override
    public PlaceSearchResult.Success search(PlaceQuery query) {
      calls++;
      lastQuery = query;
      return new PlaceSearchResult.Success(listings, NOW, 0);
    }
  }

  @BeforeEach
  void resetAdapter() {
    places.listings = List.of();
    places.calls = 0;
  }

  private SearchRequest request() {
    return new SearchRequest(
        new Navigation(
            UUID.randomUUID().toString(),
            new Destination("가상 목적지", 37.544, 127.055),
            NOW.plusSeconds(3600),
            NOW,
            NavigationSource.MOCK),
        new Party(2, 1),
        15,
        Set.of("parking"),
        Set.of("한식"),
        false);
  }

  private List<PlaceSummary> listings(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                new PlaceSummary(
                    PlaceSummary.Provider.MOCK,
                    "fictional-" + i,
                    "가상 테스트 식당 " + i,
                    null,
                    37.544,
                    127.055,
                    null,
                    null,
                    null,
                    null))
        .toList();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 5})
  void httpSearchPersistsZeroToThreeCandidatesWithoutPaddingOrInventingFacts(int count)
      throws Exception {
    places.listings = listings(count);
    var response =
        http.perform(
                post("/api/v1/searches")
                    .principal(() -> "test-owner")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("MOCK"))
            .andExpect(jsonPath("$.candidates.length()").value(Math.min(3, count)))
            .andReturn();
    Search created = json.readValue(response.getResponse().getContentAsByteArray(), Search.class);
    Search saved = service.getSearch(created.id);
    assertThat(saved.candidates).hasSize(Math.min(3, count));
    assertThat(saved.candidates)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.facts).isEmpty();
              assertThat(candidate.cuisine).isEmpty();
              assertThat(candidate.missing)
                  .contains(
                      "childFriendly", "parking", "arrivalWaitMinutes", "reservationAvailable");
              assertThat(candidate.bookable).isFalse();
            });
    assertThat(places.lastQuery).isEqualTo(new PlaceQuery(37.544, 127.055, 1000, 15));
  }

  @Test
  void replayUsesTheOriginalSnapshotWithoutQueryingAgainAndDifferentPayloadIsRejected() {
    String key = UUID.randomUUID().toString();
    SearchRequest request = request();
    places.listings = listings(1);
    Search first = service.search(key, request);
    places.listings = listings(2);
    Search replay = service.search(key, request);
    assertThat(replay.id).isEqualTo(first.id);
    assertThat(replay.candidates).hasSize(1);
    assertThat(places.calls).isEqualTo(1);
    assertThatThrownBy(() -> service.search(key, request()))
        .isInstanceOfSatisfying(
            ApiError.class, error -> assertThat(error.code).isEqualTo("KEY_REUSED"));
    assertThat(places.calls).isEqualTo(1);
  }
}
