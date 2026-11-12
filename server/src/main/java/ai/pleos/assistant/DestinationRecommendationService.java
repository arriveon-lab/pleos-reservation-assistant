package ai.pleos.assistant;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Destination-only transient shortlist. Optional one-shot web research; no telephone or booking.
 */
public final class DestinationRecommendationService implements AutoCloseable {
  private static final WebGateway NO_WEB =
      (request, deadline, current) ->
          WebRecommendation.empty(
              WebRecommendation.Mode.WEB_SEARCH, WebRecommendation.State.APPROVAL_REQUIRED);

  @FunctionalInterface
  public interface WebGateway {
    WebRecommendation.Result search(
        WebRecommendation.Request request, java.time.Instant deadline, BooleanSupplier current);
  }

  public record Recommendation(
      PlaceRecommendationPlanner.Plan listing,
      WebRecommendation.State webState,
      WebRecommendation.FailureReason webFailureReason,
      int webCandidateCount,
      LookupState identityLookupState,
      WebListingRecommendationJoiner.Result matches,
      List<WebRecommendation.Candidate> webRecommendations,
      Set<String> preferredCuisines) {
    public Recommendation(
        PlaceRecommendationPlanner.Plan listing,
        WebRecommendation.State webState,
        WebRecommendation.FailureReason webFailureReason,
        int webCandidateCount,
        LookupState identityLookupState,
        WebListingRecommendationJoiner.Result matches,
        List<WebRecommendation.Candidate> webRecommendations) {
      this(
          listing,
          webState,
          webFailureReason,
          webCandidateCount,
          identityLookupState,
          matches,
          webRecommendations,
          Set.of());
    }

    public Recommendation {
      webRecommendations = List.copyOf(webRecommendations);
      preferredCuisines = Set.copyOf(preferredCuisines);
    }
  }

  public enum LookupState {
    NOT_RUN,
    MATCHED,
    EMPTY,
    FAILED
  }

  public record SourceInfo(
      PlaceSummary.Provider provider,
      String datasetId,
      String catalogUrl,
      String licenseUrl,
      String attribution,
      java.time.Instant acquiredAt,
      java.time.Instant sourceSnapshotAt) {}

  private final PlaceSearchAdapter places;
  private final PlaceSummary.Provider provider;
  private final SourceInfo source;
  private final Clock clock;
  private final PlaceRecommendationPlanner planner;
  private final WebListingRecommendationJoiner joiner;
  private final WebGateway web;
  private final RecommendationJobRegistry<Recommendation> jobs;

  public static DestinationRecommendationService webOnly(
      Clock clock, Executor executor, WebGateway web) {
    return new DestinationRecommendationService(
        null,
        PlaceSummary.Provider.WEB_SEARCH,
        new SourceInfo(PlaceSummary.Provider.WEB_SEARCH, null, null, null, "웹 근거", null, null),
        clock,
        executor,
        web);
  }

  public DestinationRecommendationService(
      SeoulPlaceSearchAdapter places, Clock clock, Executor executor) {
    this(places, clock, executor, NO_WEB);
  }

  public DestinationRecommendationService(
      SeoulPlaceSearchAdapter places, Clock clock, Executor executor, WebGateway web) {
    this(
        places,
        PlaceSummary.Provider.SEOUL_OPEN_DATA,
        new SourceInfo(
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            places.provenance().datasetId(),
            places.provenance().catalogUrl(),
            places.provenance().licenseUrl(),
            places.provenance().attribution(),
            places.provenance().acquiredAt(),
            places.provenance().sourceSnapshotAt()),
        clock,
        executor,
        web);
  }

  public DestinationRecommendationService(
      KakaoPlaceSearchAdapter places, Clock clock, Executor executor) {
    this(places, clock, executor, NO_WEB);
  }

  public DestinationRecommendationService(
      KakaoPlaceSearchAdapter places, Clock clock, Executor executor, WebGateway web) {
    this(
        places,
        PlaceSummary.Provider.KAKAO,
        new SourceInfo(PlaceSummary.Provider.KAKAO, null, null, null, "Kakao Local", null, null),
        clock,
        executor,
        web);
  }

  DestinationRecommendationService(
      PlaceSearchAdapter places,
      PlaceSummary.Provider provider,
      SourceInfo source,
      Clock clock,
      Executor executor) {
    this(places, provider, source, clock, executor, NO_WEB);
  }

  DestinationRecommendationService(
      PlaceSearchAdapter places,
      PlaceSummary.Provider provider,
      SourceInfo source,
      Clock clock,
      Executor executor,
      WebGateway web) {
    if (places == null && provider != PlaceSummary.Provider.WEB_SEARCH)
      throw new IllegalArgumentException("Place source required");
    this.places = places;
    this.provider = Objects.requireNonNull(provider);
    this.source = Objects.requireNonNull(source);
    this.clock = Objects.requireNonNull(clock);
    planner = new PlaceRecommendationPlanner(Objects.requireNonNull(clock));
    joiner = new WebListingRecommendationJoiner(clock);
    this.web = Objects.requireNonNull(web);
    jobs = new RecommendationJobRegistry<>(clock, executor);
  }

  public UUID start(
      String owner,
      UUID viewId,
      long generation,
      String area,
      PlaceQuery destinationWindow,
      PlaceRecommendationPlanner.Requirements requirements) {
    if (!WebRecommendation.text(area, 80)) throw new IllegalArgumentException("Invalid area");
    Objects.requireNonNull(destinationWindow);
    Objects.requireNonNull(requirements);
    if (provider == PlaceSummary.Provider.WEB_SEARCH) {
      return startWeb(
          owner,
          new WebRecommendation.Request(
              viewId,
              generation,
              area,
              webNeeds(requirements.requiredFeatures()),
              false,
              null,
              requirements.preferredCuisines()));
    }
    return jobs.submit(
        owner,
        viewId,
        generation,
        current -> {
          if (!current.getAsBoolean()) return null;
          PlaceSearchResult listing = places.search(destinationWindow);
          if (!current.getAsBoolean()) return null;
          var plan = planner.evaluate(provider, destinationWindow, listing, requirements);
          if (!(listing instanceof PlaceSearchResult.Success found)
              || plan.state() != PlaceRecommendationPlanner.State.RESULTS)
            return current.getAsBoolean()
                ? new Recommendation(
                    plan,
                    WebRecommendation.State.EMPTY,
                    null,
                    0,
                    LookupState.NOT_RUN,
                    null,
                    List.of())
                : null;
          Set<WebRecommendation.Need> needs = webNeeds(requirements.requiredFeatures());
          var request = new WebRecommendation.Request(viewId, generation, area, needs, false);
          var webResult = web.search(request, clock.instant().plusSeconds(30), current);
          if (!current.getAsBoolean()) return null;
          if (webResult == null)
            return new Recommendation(
                plan,
                WebRecommendation.State.FAILED,
                WebRecommendation.FailureReason.TRANSPORT_ERROR,
                0,
                LookupState.NOT_RUN,
                null,
                List.of());
          LookupState lookupState = LookupState.NOT_RUN;
          var matches =
              webResult.state() == WebRecommendation.State.RESULTS
                  ? joiner.join(destinationWindow, found, provider, webResult, needs)
                  : null;
          if (matches != null
              && matches.state() != WebListingRecommendationJoiner.State.INVALID_EVIDENCE
              && matches.candidates().size() < 3
              && places instanceof PlaceIdentityLookup lookup) {
            var missing = missingIdentities(webResult, matches);
            if (!missing.isEmpty() && current.getAsBoolean()) {
              var resolved = lookup.lookup(destinationWindow, missing, current);
              if (resolved instanceof PlaceSearchResult.Success extra && current.getAsBoolean()) {
                lookupState = extra.places().isEmpty() ? LookupState.EMPTY : LookupState.MATCHED;
                var byId = new LinkedHashMap<String, PlaceSummary>();
                for (PlaceSummary place : extra.places())
                  byId.putIfAbsent(place.providerPlaceId(), place);
                for (PlaceSummary place : found.places()) {
                  if (relevant(place, webResult.candidates()))
                    byId.putIfAbsent(place.providerPlaceId(), place);
                }
                var merged = new ArrayList<PlaceSummary>();
                for (PlaceSummary place : byId.values()) {
                  if (merged.size() >= destinationWindow.limit()) break;
                  merged.add(place);
                }
                matches =
                    joiner.join(
                        destinationWindow,
                        new PlaceSearchResult.Success(merged, extra.fetchedAt(), 0),
                        provider,
                        webResult,
                        needs);
              } else if (resolved instanceof PlaceSearchResult.Failure) {
                lookupState = LookupState.FAILED;
              }
            }
          }
          return new Recommendation(
              plan,
              webResult.state(),
              webResult.failureReason(),
              webResult.candidates().size(),
              lookupState,
              matches,
              List.of());
        });
  }

  private static List<PlaceIdentityLookup.Identity> missingIdentities(
      WebRecommendation.Result web, WebListingRecommendationJoiner.Result matches) {
    return web.candidates().stream()
        .filter(
            report ->
                matches.candidates().stream()
                    .noneMatch(
                        matched ->
                            same(report.name(), matched.name())
                                && same(report.address(), matched.address())))
        .limit(3)
        .map(report -> new PlaceIdentityLookup.Identity(report.name(), report.address()))
        .toList();
  }

  private static boolean relevant(PlaceSummary place, List<WebRecommendation.Candidate> reports) {
    return reports.stream()
        .anyMatch(
            report -> same(place.name(), report.name()) && same(place.address(), report.address()));
  }

  private static boolean same(String left, String right) {
    return left != null
        && right != null
        && PlaceDetailEnricher.canonical(left).equals(PlaceDetailEnricher.canonical(right));
  }

  private static Set<WebRecommendation.Need> webNeeds(Set<String> features) {
    var needs = java.util.EnumSet.of(WebRecommendation.Need.PARKING);
    for (String feature : features) {
      needs.add(
          switch (feature) {
            case "parking" -> WebRecommendation.Need.PARKING;
            case "highChair" -> WebRecommendation.Need.HIGH_CHAIR;
            case "stepFreeAccess" -> WebRecommendation.Need.STEP_FREE_ACCESS;
            case "childFriendly" -> WebRecommendation.Need.CHILD_FRIENDLY;
            default -> throw new IllegalArgumentException("Unsupported feature");
          });
    }
    return Set.copyOf(needs);
  }

  UUID startWeb(String owner, WebRecommendation.Request request) {
    if (provider != PlaceSummary.Provider.WEB_SEARCH)
      throw new IllegalStateException("Web source required");
    return jobs.submit(
        owner,
        request.viewId(),
        request.generation(),
        current -> {
          if (!current.getAsBoolean()) return null;
          var result = web.search(request, clock.instant().plusSeconds(30), current);
          if (!current.getAsBoolean()) return null;
          if (result == null)
            return new Recommendation(
                null,
                WebRecommendation.State.FAILED,
                WebRecommendation.FailureReason.TRANSPORT_ERROR,
                0,
                LookupState.NOT_RUN,
                null,
                List.of());
          return new Recommendation(
              null,
              result.state(),
              result.failureReason(),
              result.candidates().size(),
              LookupState.NOT_RUN,
              null,
              result.candidates(),
              request.preferredCuisines());
        });
  }

  public Optional<RecommendationJobRegistry.Snapshot<Recommendation>> poll(
      String owner, UUID taskId) {
    return jobs.poll(owner, taskId);
  }

  public void invalidate(String owner, UUID viewId) {
    jobs.invalidate(owner, viewId);
  }

  public SourceInfo source() {
    return source;
  }

  @Override
  public void close() {
    jobs.close();
    if (places instanceof KakaoPlaceSearchAdapter kakao) kakao.close();
  }
}
