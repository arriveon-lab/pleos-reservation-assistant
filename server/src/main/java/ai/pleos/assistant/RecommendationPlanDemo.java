package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.util.Set;

/**
 * Fictional adapter only. No credential, external provider, HTTP server or database is initialized.
 */
public final class RecommendationPlanDemo {
  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("This demo accepts no arguments");
    Clock clock = Clock.systemUTC();
    var query = new PlaceQuery(37.544, 127.055, 1000, 15);
    var requirements =
        new PlaceRecommendationPlanner.Requirements(
            new Model.Party(2, 1), 15, Set.of("parking"), Set.of("한식"));
    var plan =
        new PlaceRecommendationPlanner(clock)
            .evaluate(
                PlaceSummary.Provider.MOCK,
                query,
                new MockPlaceSearchAdapter(clock).search(query),
                requirements);
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    System.out.println("RECOMMENDATION_PLAN " + json.writeValueAsString(plan));
  }
}
