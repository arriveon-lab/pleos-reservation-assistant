package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fictional full example. No real transport, API key, database, HTTP server or external call. */
public final class PlaceDetailDemo {
  public record DemoResult(
      String mode,
      GptPlaceSourceDiscovery.Result sourceDiscovery,
      PlaceDetailRecommendationService.Result enrichment) {}

  static DemoResult sample(Clock clock) throws Exception {
    var json = new ObjectMapper();
    var query = new PlaceQuery(37.544, 127.055, 1000, 15);
    var raw = new MockPlaceSearchAdapter(clock).search(query);
    // The shared mock intentionally has no address; this demo supplies fictional identities.
    var listing =
        new PlaceSearchResult.Success(
            raw.places().stream()
                .map(
                    p ->
                        new PlaceSummary(
                            p.provider(),
                            p.providerPlaceId(),
                            p.name(),
                            p.cuisine(),
                            p.latitude(),
                            p.longitude(),
                            "가상시 가상구 가상로 " + p.providerPlaceId().substring(5),
                            null,
                            null,
                            null))
                .toList(),
            raw.fetchedAt(),
            0);
    var place = listing.places().getFirst();
    var identity =
        new PlaceDetailEnricher.Identity(
            place.name(), place.address(), PlaceDetailEnricher.Origin.MOCK);
    var url = URI.create("https://merchant.example.invalid/fictional");
    var response = json.createObjectNode().put("status", "completed");
    var output = response.putArray("output");
    output.addObject().put("type", "web_search_call").put("status", "completed");
    output
        .addObject()
        .put("type", "message")
        .put("role", "assistant")
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put("text", "가상 매장 출처 후보")
        .putArray("annotations")
        .addObject()
        .put("type", "url_citation")
        .put("url", url.toString())
        .put("title", "가상 매장 안내");
    var discovery =
        new GptPlaceSourceDiscovery(
            clock,
            (body, key, timeout) -> new GptPlaceSourceDiscovery.Response(200, response.toString()),
            () -> "fictional-demo-key");
    var sources =
        discovery.discover(
            identity,
            new GptPlaceSourceDiscovery.Permit(
                identity, "fictional-mock-model", clock.instant().plusSeconds(30)),
            clock.instant().plusSeconds(30),
            () -> true);
    // This fixture approval is supplied independently; GPT citations never approve themselves.
    var approved = new PlaceDetailEnricher.Source(url, "merchant.example.invalid");
    var data =
        json.createObjectNode()
            .put("@context", "https://schema.org")
            .put("@type", "Restaurant")
            .put("name", place.name())
            .put("address", place.address())
            .put("servesCuisine", "한식")
            .put("openingHours", "Mo-Su 11:00-21:00")
            .put("priceRange", "10000–20000 KRW")
            .put("hasMenu", "/menu")
            .put("acceptsReservations", true);
    data.putArray("amenityFeature").addObject().put("name", "parking").put("value", true);
    String html = "<script type='application/ld+json'>" + data + "</script>";
    var service =
        new PlaceDetailRecommendationService(
            clock,
            PlaceDetailEnricher.Mode.MOCK,
            (source, deadline) ->
                new PlaceDetailEnricher.Page(
                    source.url(), html, clock.instant(), PlaceDetailEnricher.Mode.MOCK));
    var result =
        service.evaluate(
            1,
            PlaceSummary.Provider.MOCK,
            query,
            listing,
            new PlaceRecommendationPlanner.Requirements(
                new Model.Party(2, 1), 15, Set.of("parking"), Set.of("한식")),
            Map.of(place.providerPlaceId(), List.of(approved)),
            () -> true);
    return new DemoResult("MOCK", sources, result);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0)
      throw new IllegalArgumentException("MOCK detail demo accepts no arguments");
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    System.out.println("PLACE_DETAILS_MOCK " + json.writeValueAsString(sample(Clock.systemUTC())));
  }
}
