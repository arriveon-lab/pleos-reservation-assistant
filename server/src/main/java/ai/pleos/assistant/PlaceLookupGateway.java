package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads structured evidence from the operator-configured loopback place lookup service. */
final class PlaceLookupGateway
    implements DestinationRecommendationService.WebGateway, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(PlaceLookupGateway.class);
  private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(12);
  private final Clock clock;
  private final RuntimeWebRecommendationGateway web;
  private final boolean fallback;
  private final boolean enrich;
  private final boolean gumiCatalogEnabled;
  private final String token;
  private final GptWebSearchHttpTransport transport;
  private final ObjectMapper json = new ObjectMapper();
  private final WebRecommendationParser parser = new WebRecommendationParser();

  PlaceLookupGateway(
      Clock clock,
      int port,
      String token,
      RuntimeWebRecommendationGateway web,
      boolean fallback,
      boolean enrich,
      boolean gumiCatalogEnabled) {
    if (port < 1 || port > 65535 || token == null || !token.matches("[A-Za-z0-9_-]{32,128}"))
      throw new IllegalArgumentException("Place lookup requires a loopback port and service token");
    this.clock = clock;
    this.web = web;
    this.fallback = fallback;
    this.enrich = enrich;
    this.gumiCatalogEnabled = gumiCatalogEnabled;
    this.token = token;
    this.transport =
        new GptWebSearchHttpTransport(URI.create("http://127.0.0.1:" + port + "/places"));
  }

  @Override
  public Result search(Request request, Instant deadline, BooleanSupplier current) {
    if (!current.getAsBoolean()) return empty(Mode.WEB_SEARCH, State.CANCELLED);
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative()) return empty(Mode.WEB_SEARCH, State.EXPIRED);
    Duration timeout = remaining.compareTo(LOOKUP_TIMEOUT) < 0 ? remaining : LOOKUP_TIMEOUT;
    if (gumiCatalogEnabled) {
      var catalog = GumiRestaurantCatalog.search(request, clock);
      if (catalog.isPresent()) return catalog.orElseThrow();
    }
    var category = request.venueCategory();
    var query =
        json.valueToTree(
            Map.of(
                "destination", request.area(),
                "venueCategory", category == null ? "ALL" : category.name(),
                "preferredCuisines", request.preferredCuisines(),
                "requiredNeeds", request.requiredNeeds()));
    Result result;
    boolean textSearch = false;
    try {
      var response = transport.post(query.toString(), token, timeout);
      textSearch = response.status() == 422;
      if (response.status() == 200) {
        result = parser.parsePlaceLookup(response.json(), request, clock.instant());
      } else {
        var reason =
            switch (response.status()) {
              case 401, 403 -> FailureReason.HTTP_UNAUTHORIZED;
              case 404 -> FailureReason.HTTP_BAD_REQUEST;
              case 429 -> FailureReason.HTTP_RATE_LIMITED;
              default -> FailureReason.HTTP_REJECTED;
            };
        result = failed(Mode.WEB_SEARCH, reason);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return empty(Mode.WEB_SEARCH, State.CANCELLED);
    } catch (Exception unavailable) {
      result = failed(Mode.WEB_SEARCH, FailureReason.TRANSPORT_ERROR);
    }
    if (!current.getAsBoolean()) return empty(Mode.WEB_SEARCH, State.CANCELLED);
    if (!clock.instant().isBefore(deadline)) return empty(Mode.WEB_SEARCH, State.EXPIRED);
    if (textSearch && fallback) return web.search(request, deadline, current);
    if (!enrich || (result.state() != State.RESULTS && result.state() != State.FILTERED))
      return result;
    var detailed =
        web.discover(
            request,
            deadline,
            current,
            candidates -> verifyBranches(query, candidates, deadline, current));
    logDetailOutcome(detailed);
    return detailed.state() == State.FAILED
            || detailed.state() == State.EMPTY
            || detailed.state() == State.APPROVAL_REQUIRED
        ? result
        : detailed;
  }

  private Set<String> verifyBranches(
      JsonNode query, JsonNode candidates, Instant deadline, BooleanSupplier current) {
    var remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative() || !current.getAsBoolean()) return Set.of();
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) query.deepCopy();
    var branches = payload.putArray("verifyBranches");
    for (var candidate : candidates) {
      String name = candidate.path("name").asText(null);
      String address = candidate.path("address").asText(null);
      if (text(name, 150) && text(address, 250))
        branches.addObject().put("name", name.strip()).put("address", address.strip());
    }
    if (branches.isEmpty()) return Set.of();
    try {
      var response =
          transport.post(
              payload.toString(),
              token,
              remaining.compareTo(LOOKUP_TIMEOUT) < 0 ? remaining : LOOKUP_TIMEOUT);
      if (response.status() != 200) return Set.of();
      var matched = json.readTree(response.json()).path("verified");
      if (!matched.isArray() || matched.size() > branches.size()) return Set.of();
      var verified = new HashSet<String>();
      for (var branch : matched) {
        String name = branch.path("name").asText(null);
        String address = branch.path("address").asText(null);
        if (text(name, 150) && text(address, 250))
          verified.add(WebRecommendationParser.identityKey(name, address));
      }
      return verified;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Set.of();
    } catch (Exception unavailable) {
      return Set.of();
    }
  }

  private static void logDetailOutcome(Result detailed) {
    var candidates = detailed.candidates();
    long openingHours =
        candidates.stream()
            .filter(place -> place.businessDetails().containsKey(BusinessDetailKind.OPENING_HOURS))
            .count();
    long breakTimes =
        candidates.stream()
            .filter(place -> place.businessDetails().containsKey(BusinessDetailKind.BREAK_TIME))
            .count();
    long parking =
        candidates.stream()
            .filter(
                place ->
                    place.assessments().stream()
                        .anyMatch(
                            item -> item.need() == Need.PARKING && !item.evidence().isEmpty()))
            .count();
    LOG.info(
        "Address detail lookup: state={}, reason={}, candidates={}, hours={}, breakTime={}, parking={}",
        detailed.state(),
        detailed.failureReason(),
        candidates.size(),
        openingHours,
        breakTimes,
        parking);
  }

  @Override
  public void close() {
    transport.close();
  }
}
