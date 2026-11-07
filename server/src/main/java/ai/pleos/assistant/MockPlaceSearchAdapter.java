package ai.pleos.assistant;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * No HTTP client or contact details. The fixed fictional pilot does not simulate geographic recall.
 */
@Component
public class MockPlaceSearchAdapter implements PlaceSearchAdapter {
  private final Clock clock;
  private static final List<PlaceSummary> FIXTURES =
      List.of(
          place("mock-1", "가상 성수 가족식당", "한식"),
          place("mock-2", "가상 서울숲 파스타", "양식"),
          place("mock-3", "가상 성수 국수집", "한식"));

  public MockPlaceSearchAdapter(Clock clock) {
    this.clock = clock;
  }

  private static PlaceSummary place(String id, String name, String cuisine) {
    return new PlaceSummary(
        PlaceSummary.Provider.MOCK, id, name, cuisine, 37.544, 127.055, null, null, null, null);
  }

  @Override
  public PlaceSearchResult.Success search(PlaceQuery query) {
    return new PlaceSearchResult.Success(
        FIXTURES.stream().limit(query.limit()).toList(), clock.instant(), 0);
  }
}
