package ai.pleos.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Separate internal result; existing v1 and preview 0.2.0 contracts are not extended implicitly.
 */
public final class PlaceDetailRecommendationService {
  public enum State {
    READY,
    CANCELLED,
    EXPIRED,
    FAILED
  }

  public record CandidateDetail(int ordinal, PlaceDetailEnricher.Report detailReport) {}

  public record Result(
      long generation,
      State state,
      Instant displayValidUntil,
      PlaceRecommendationPlanner.Plan recommendationPlan,
      List<CandidateDetail> candidateDetails) {
    public Result {
      candidateDetails = List.copyOf(candidateDetails);
    }
  }

  private final Clock clock;
  private final PlaceDetailEnricher enricher;

  public PlaceDetailRecommendationService(
      Clock clock, PlaceDetailEnricher.Mode mode, PlaceDetailEnricher.PageAdapter adapter) {
    this.clock = Objects.requireNonNull(clock);
    enricher = new PlaceDetailEnricher(clock, mode, adapter);
  }

  public Result evaluate(
      long generation,
      PlaceSummary.Provider provider,
      PlaceQuery query,
      PlaceSearchResult listing,
      PlaceRecommendationPlanner.Requirements requirements,
      Map<String, List<PlaceDetailEnricher.Source>> approvedSources,
      BooleanSupplier current) {
    if (generation < 1 || generation > 9_007_199_254_740_991L)
      throw new IllegalArgumentException("Invalid generation");
    Objects.requireNonNull(approvedSources);
    Objects.requireNonNull(current);
    if (!current.getAsBoolean()) return hidden(generation, State.CANCELLED);
    var plan =
        new PlaceRecommendationPlanner(clock).evaluate(provider, query, listing, requirements);
    if (plan.state() == PlaceRecommendationPlanner.State.FAILED)
      return hidden(generation, State.FAILED);
    Instant expiry = plan.displayValidUntil();
    var required = new java.util.HashSet<PlaceDetailEnricher.Field>();
    if (requirements.requiredFeatures().contains("parking"))
      required.add(PlaceDetailEnricher.Field.PARKING);
    if (requirements.requiredFeatures().contains("childFriendly")
        || requirements.party().children() > 0)
      required.add(PlaceDetailEnricher.Field.CHILD_FRIENDLY);
    var output = new ArrayList<CandidateDetail>();
    List<PlaceSummary> places = ((PlaceSearchResult.Success) listing).places();
    for (var candidate : plan.candidates()) {
      var matches =
          places.stream()
              .filter(
                  p ->
                      p.name().equals(candidate.name())
                          && Objects.equals(p.address(), candidate.address()))
              .distinct()
              .toList();
      var origin =
          switch (provider) {
            case MOCK -> PlaceDetailEnricher.Origin.MOCK;
            case KAKAO -> PlaceDetailEnricher.Origin.KAKAO;
            case SEOUL_OPEN_DATA -> PlaceDetailEnricher.Origin.APPROVED_PUBLIC;
          };
      boolean unambiguous = matches.size() == 1;
      var identity =
          new PlaceDetailEnricher.Identity(
              candidate.name(), unambiguous ? candidate.address() : null, origin);
      var sources =
          unambiguous
              ? approvedSources.getOrDefault(matches.getFirst().providerPlaceId(), List.of())
              : List.<PlaceDetailEnricher.Source>of();
      var report = enricher.enrich(identity, sources, expiry, Set.copyOf(required), current);
      if (report.outcome() == PlaceDetailEnricher.Outcome.CANCELLED)
        return hidden(generation, State.CANCELLED);
      if (report.outcome() == PlaceDetailEnricher.Outcome.EXPIRED)
        return hidden(generation, State.EXPIRED);
      if (report.validUntil() != null && report.validUntil().isBefore(expiry))
        expiry = report.validUntil();
      output.add(new CandidateDetail(candidate.ordinal(), report));
    }
    if (!current.getAsBoolean()) return hidden(generation, State.CANCELLED);
    if (!clock.instant().isBefore(expiry)) return hidden(generation, State.EXPIRED);
    return new Result(generation, State.READY, expiry, plan, output);
  }

  private Result hidden(long generation, State state) {
    return new Result(generation, state, null, null, List.of());
  }
}
