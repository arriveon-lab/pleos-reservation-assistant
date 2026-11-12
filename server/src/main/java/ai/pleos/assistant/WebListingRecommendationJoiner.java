package ai.pleos.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Joins independently searched web reports to a transient place listing. Listing names, addresses
 * and phone numbers are never sent to GPT by this class. A name/address match only identifies a
 * candidate for display; it does not verify a merchant, phone number, or condition.
 */
public final class WebListingRecommendationJoiner {
  public enum State {
    RESULTS,
    NO_MATCH,
    INVALID_EVIDENCE
  }

  public record Candidate(
      int ordinal,
      String name,
      String address,
      int straightLineDistanceMeters,
      WebRecommendation.Fit fit,
      List<WebRecommendation.Assessment> assessments,
      List<WebRecommendation.Evidence> reviews,
      List<WebRecommendation.Need> remainingQuestions,
      boolean bookable,
      boolean inquiryDispatchAllowed) {
    public Candidate {
      assessments = List.copyOf(assessments);
      reviews = List.copyOf(reviews);
      remainingQuestions = List.copyOf(remainingQuestions);
    }
  }

  public record Result(State state, Instant validUntil, List<Candidate> candidates) {
    public Result {
      candidates = List.copyOf(candidates);
    }
  }

  private final Clock clock;

  public WebListingRecommendationJoiner(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public Result join(
      PlaceQuery query,
      PlaceSearchResult.Success listing,
      PlaceSummary.Provider provider,
      WebRecommendation.Result web,
      Set<WebRecommendation.Need> requiredNeeds) {
    Objects.requireNonNull(query);
    Objects.requireNonNull(listing);
    Objects.requireNonNull(provider);
    Objects.requireNonNull(web);
    Objects.requireNonNull(requiredNeeds);
    Instant now = clock.instant();
    if (!requiredNeeds.contains(WebRecommendation.Need.PARKING)
        || web.mode() != WebRecommendation.Mode.WEB_SEARCH
        || web.state() != WebRecommendation.State.RESULTS
        || web.observedAt() == null
        || web.observedAt().isAfter(now)
        || web.validUntil() == null
        || !now.isBefore(web.validUntil())
        || web.validUntil().isAfter(web.observedAt().plusSeconds(300))
        || listing.fetchedAt().isAfter(now)
        || !listing.fetchedAt().isAfter(now.minusSeconds(300))
        || listing.places().size() > query.limit()) {
      return new Result(State.INVALID_EVIDENCE, null, List.of());
    }
    var byIdentity = new HashMap<String, PlaceSummary>();
    var ambiguous = new HashSet<String>();
    for (PlaceSummary place : listing.places()) {
      if (place.provider() != provider
          || place.address() == null
          || PlaceRecommendationPlanner.distance(query, place) > query.radiusMeters() + 30)
        continue;
      String identity = identity(place.name(), place.address());
      if (byIdentity.putIfAbsent(identity, place) != null) ambiguous.add(identity);
    }
    var candidates = new ArrayList<Candidate>();
    for (WebRecommendation.Candidate report : web.candidates()) {
      String identity = identity(report.name(), report.address());
      PlaceSummary place = byIdentity.get(identity);
      if (place == null || ambiguous.contains(identity)) continue;
      if (report.assessments().size() != requiredNeeds.size()
          || !report.assessments().stream()
              .map(WebRecommendation.Assessment::need)
              .collect(java.util.stream.Collectors.toSet())
              .equals(requiredNeeds)) return new Result(State.INVALID_EVIDENCE, null, List.of());
      if (report.assessments().stream().anyMatch(a -> a.verdict() == WebRecommendation.Verdict.NO))
        continue;
      candidates.add(
          new Candidate(
              candidates.size() + 1,
              place.name(),
              place.address(),
              (int) Math.round(PlaceRecommendationPlanner.distance(query, place)),
              report.fit(),
              report.assessments(),
              report.reviews(),
              report.remainingQuestions(),
              false,
              false));
      if (candidates.size() == 3) break;
    }
    Instant listingExpiry = listing.fetchedAt().plusSeconds(300);
    Instant until = web.validUntil().isBefore(listingExpiry) ? web.validUntil() : listingExpiry;
    return new Result(candidates.isEmpty() ? State.NO_MATCH : State.RESULTS, until, candidates);
  }

  private static String identity(String name, String address) {
    return PlaceDetailEnricher.canonical(name) + "\u0000" + PlaceDetailEnricher.canonical(address);
  }
}
