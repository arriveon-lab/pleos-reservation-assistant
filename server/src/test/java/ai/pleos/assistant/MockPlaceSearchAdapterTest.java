package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MockPlaceSearchAdapterTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private final MockPlaceSearchAdapter adapter =
      new MockPlaceSearchAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
  private final MockCandidateFactory factory = new MockCandidateFactory();

  @Test
  void fixturesPreserveProvenanceAndDoNotShareMutableCandidateState() {
    var result = adapter.search(new PlaceQuery(37.544, 127.055, 1000, 15));
    var first = factory.create(result);
    assertThat(first).extracting(c -> c.id).containsExactly("mock-1", "mock-2", "mock-3");
    assertThat(first).extracting(c -> c.name).allMatch(name -> name.startsWith("가상 "));
    assertThat(first.getFirst().facts).containsOnlyKeys("childFriendly");
    Fact child = first.getFirst().facts.get("childFriendly");
    assertThat(child.mode()).isEqualTo(Mode.MOCK);
    assertThat(child.source()).isEqualTo("MOCK_FIXTURE");
    assertThat(child.evidenceRef()).isEqualTo("mock-1");
    assertThat(child.observedAt()).isEqualTo(NOW);
    assertThat(child.validUntil()).isEqualTo(NOW.plusSeconds(300));
    assertThat(child.scope()).isEqualTo(Scope.GENERAL);
    assertThat(first.get(1).facts).containsOnlyKeys("childFriendly", "parking");
    assertThat(first.get(2).facts).containsOnlyKeys("parking");
    first.getFirst().name = "changed";
    first.getFirst().facts.clear();
    first.clear();
    var next = factory.create(result);
    assertThat(next.getFirst().name).isEqualTo("가상 성수 가족식당");
    assertThat(next.getFirst().facts).containsKey("childFriendly");
    assertThat(result.places())
        .allSatisfy(
            place -> {
              assertThat(place.phone()).isNull();
              assertThat(place.placeUrl()).isNull();
            });
  }

  @Test
  void successOwnsAnImmutableSnapshotAndMockHonorsTheRequestedLimit() {
    var result = adapter.search(new PlaceQuery(37.544, 127.055, 1000, 1));
    var original = new ArrayList<>(result.places());
    var copy = new PlaceSearchResult.Success(original, NOW, 0);
    original.clear();
    assertThat(copy.places()).hasSize(1);
    assertThatThrownBy(() -> copy.places().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void kakaoListingsCannotAcquireMockFactsOrEnterTheMockWorkflow() {
    var place =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            "mock-1",
            "가상 응답",
            "한식",
            37.544,
            127.055,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> factory.create(new PlaceSearchResult.Success(List.of(place), NOW, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "NaN,127,1000,15",
    "Infinity,127,1000,15",
    "91,127,1000,15",
    "37,181,1000,15",
    "37,127,0,15",
    "37,127,20001,15",
    "37,127,1000,0",
    "37,127,1000,16"
  })
  void invalidQueriesDoNotBecomeDefaultCoordinates(
      double latitude, double longitude, int radius, int limit) {
    assertThatThrownBy(() -> new PlaceQuery(latitude, longitude, radius, limit))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
