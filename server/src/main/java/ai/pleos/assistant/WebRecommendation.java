package ai.pleos.assistant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Web reports are transient; only Request and user-authored Feedback may enter the feedback store.
 */
public final class WebRecommendation {
  private WebRecommendation() {}

  public enum Need {
    PET_FRIENDLY,
    INDOOR_PET_FRIENDLY,
    PARKING,
    CHILD_FRIENDLY,
    HIGH_CHAIR,
    STEP_FREE_ACCESS
  }

  public enum Verdict {
    YES,
    NO,
    UNKNOWN,
    CONFLICT
  }

  public enum VenueCategory {
    RESTAURANT,
    CAFE,
    UNKNOWN
  }

  public enum BusinessDetailKind {
    CUISINE,
    MENU,
    OPENING_HOURS,
    BREAK_TIME
  }

  public enum SourceType {
    MERCHANT_PAGE,
    VISITOR_REVIEW,
    OTHER
  }

  public enum Fit {
    POTENTIAL_MATCH,
    NEEDS_CONFIRMATION
  }

  public enum State {
    RESULTS,
    EMPTY,
    FILTERED,
    FAILED,
    CANCELLED,
    EXPIRED,
    APPROVAL_REQUIRED,
    ALREADY_REQUESTED
  }

  /** Safe provider diagnostics; never contains a response body, URL, credential, or user data. */
  public enum FailureReason {
    BUDGET_EXHAUSTED,
    CREDENTIAL_MISSING,
    HTTP_BAD_REQUEST,
    HTTP_UNAUTHORIZED,
    HTTP_RATE_LIMITED,
    HTTP_REJECTED,
    INCOMPLETE_RESPONSE,
    MISSING_WEB_SEARCH,
    MISSING_OUTPUT_TEXT,
    UNMATCHED_IDENTITY_SOURCE,
    INVALID_SOURCE_METADATA,
    INVALID_OUTPUT_SHAPE,
    INVALID_CANDIDATE,
    INVALID_REVIEW,
    INVALID_REPORT,
    INVALID_RESPONSE,
    TRANSPORT_ERROR
  }

  public enum Mode {
    MOCK,
    WEB_SEARCH
  }

  public enum UserFit {
    MATCH,
    PARTIAL,
    NO_MATCH
  }

  public enum FeedbackReason {
    NEEDS_MET,
    INDOOR_PREFERRED,
    PARKING_REQUIRED,
    DISTANCE_TOO_FAR,
    PRICE_TOO_HIGH
  }

  public record Request(
      UUID viewId,
      long generation,
      String area,
      Set<Need> requiredNeeds,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          boolean personalizationConsent,
      @JsonIgnore VenueCategory venueCategory,
      @JsonIgnore Set<String> preferredCuisines) {
    public Request(
        UUID viewId,
        long generation,
        String area,
        Set<Need> requiredNeeds,
        boolean personalizationConsent,
        VenueCategory venueCategory) {
      this(
          viewId, generation, area, requiredNeeds, personalizationConsent, venueCategory, Set.of());
    }

    public Request(
        UUID viewId,
        long generation,
        String area,
        Set<Need> requiredNeeds,
        boolean personalizationConsent) {
      this(viewId, generation, area, requiredNeeds, personalizationConsent, null);
    }

    public Request {
      preferredCuisines = preferredCuisines == null ? Set.of() : Set.copyOf(preferredCuisines);
      if (viewId == null
          || generation < 1
          || generation > 1_000_000_000
          || !text(area, 80)
          || requiredNeeds == null
          || requiredNeeds.isEmpty()
          || requiredNeeds.size() > Need.values().length
          || requiredNeeds.stream().anyMatch(Objects::isNull)
          || venueCategory == VenueCategory.UNKNOWN
          || !UserRecommendationIntent.CUISINES.containsAll(preferredCuisines)
          || (venueCategory == VenueCategory.CAFE && !preferredCuisines.isEmpty()))
        throw new IllegalArgumentException("Invalid web recommendation request");
      area = area.strip();
      requiredNeeds = Set.copyOf(requiredNeeds);
    }

    @Override
    public String toString() {
      return "WebRequest[private input omitted]";
    }
  }

  /** User's own statement, not an AI label or a merchant fact. No free-text provider content. */
  public record Feedback(int selectedOrdinal, UserFit fit, Set<FeedbackReason> reasons) {
    public Feedback {
      if (selectedOrdinal < 1
          || selectedOrdinal > 3
          || fit == null
          || reasons == null
          || reasons.size() > FeedbackReason.values().length
          || reasons.stream().anyMatch(Objects::isNull))
        throw new IllegalArgumentException("Invalid user feedback");
      reasons = Set.copyOf(reasons);
    }
  }

  public record Evidence(
      URI url,
      String title,
      SourceType sourceType,
      Instant observedAt,
      Instant sourceUpdatedAt,
      Instant validUntil,
      String condition,
      String certainty,
      Model.Scope scope,
      Instant appliesAt) {
    public Evidence {
      Objects.requireNonNull(url);
      Objects.requireNonNull(sourceType);
      Objects.requireNonNull(observedAt);
      Objects.requireNonNull(validUntil);
      if (!safeCitation(url)
          || !text(title, 200)
          || !text(condition, 240)
          || !"REPORTED_WEB_NOT_MERCHANT_CONFIRMATION".equals(certainty)
          || scope != Model.Scope.GENERAL
          || appliesAt != null
          || !validUntil.isAfter(observedAt)
          || (sourceUpdatedAt != null && sourceUpdatedAt.isAfter(observedAt)))
        throw new IllegalArgumentException("Invalid web evidence");
    }

    @Override
    public String toString() {
      return "WebEvidence[content omitted]";
    }
  }

  /** YES/NO reflects a cited web report, never independently verified merchant acceptance. */
  public record Assessment(Need need, Verdict verdict, List<Evidence> evidence) {
    public Assessment {
      evidence = List.copyOf(evidence);
    }
  }

  public record Candidate(
      int ordinal,
      String name,
      String address,
      URI identitySourceUrl,
      VenueCategory venueCategory,
      Fit fit,
      List<Assessment> assessments,
      List<Evidence> reviews,
      List<Need> remainingQuestions,
      boolean identityVerified,
      boolean bookable,
      boolean inquiryDispatchAllowed,
      Map<BusinessDetailKind, Evidence> businessDetails,
      @JsonInclude(JsonInclude.Include.NON_NULL) URI naverPlaceUrl,
      List<Assessment> reviewSignals) {
    public Candidate(
        int ordinal,
        String name,
        String address,
        URI identitySourceUrl,
        VenueCategory venueCategory,
        Fit fit,
        List<Assessment> assessments,
        List<Evidence> reviews,
        List<Need> remainingQuestions,
        boolean identityVerified,
        boolean bookable,
        boolean inquiryDispatchAllowed,
        Map<BusinessDetailKind, Evidence> businessDetails,
        URI naverPlaceUrl) {
      this(
          ordinal,
          name,
          address,
          identitySourceUrl,
          venueCategory,
          fit,
          assessments,
          reviews,
          remainingQuestions,
          identityVerified,
          bookable,
          inquiryDispatchAllowed,
          businessDetails,
          naverPlaceUrl,
          List.of());
    }

    public Candidate(
        int ordinal,
        String name,
        String address,
        URI identitySourceUrl,
        VenueCategory venueCategory,
        Fit fit,
        List<Assessment> assessments,
        List<Evidence> reviews,
        List<Need> remainingQuestions,
        boolean identityVerified,
        boolean bookable,
        boolean inquiryDispatchAllowed,
        Map<BusinessDetailKind, Evidence> businessDetails) {
      this(
          ordinal,
          name,
          address,
          identitySourceUrl,
          venueCategory,
          fit,
          assessments,
          reviews,
          remainingQuestions,
          identityVerified,
          bookable,
          inquiryDispatchAllowed,
          businessDetails,
          null);
    }

    public Candidate(
        int ordinal,
        String name,
        String address,
        URI identitySourceUrl,
        Fit fit,
        List<Assessment> assessments,
        List<Evidence> reviews,
        List<Need> remainingQuestions,
        boolean identityVerified,
        boolean bookable,
        boolean inquiryDispatchAllowed) {
      this(
          ordinal,
          name,
          address,
          identitySourceUrl,
          VenueCategory.UNKNOWN,
          fit,
          assessments,
          reviews,
          remainingQuestions,
          identityVerified,
          bookable,
          inquiryDispatchAllowed,
          Map.of());
    }

    public Candidate(
        int ordinal,
        String name,
        String address,
        URI identitySourceUrl,
        Fit fit,
        List<Assessment> assessments,
        List<Need> remainingQuestions,
        boolean identityVerified,
        boolean bookable,
        boolean inquiryDispatchAllowed) {
      this(
          ordinal,
          name,
          address,
          identitySourceUrl,
          fit,
          assessments,
          List.of(),
          remainingQuestions,
          identityVerified,
          bookable,
          inquiryDispatchAllowed);
    }

    public Candidate {
      venueCategory = venueCategory == null ? VenueCategory.UNKNOWN : venueCategory;
      businessDetails = businessDetails == null ? Map.of() : Map.copyOf(businessDetails);
      assessments = List.copyOf(assessments);
      reviews = List.copyOf(reviews);
      reviewSignals = reviewSignals == null ? List.of() : List.copyOf(reviewSignals);
      remainingQuestions = List.copyOf(remainingQuestions);
      if (identityVerified || bookable || inquiryDispatchAllowed)
        throw new IllegalArgumentException("Web reports cannot authorize actions");
    }

    private Candidate withOrdinal(int rank) {
      return new Candidate(
          rank,
          name,
          address,
          identitySourceUrl,
          venueCategory,
          fit,
          assessments,
          reviews,
          remainingQuestions,
          identityVerified,
          bookable,
          inquiryDispatchAllowed,
          businessDetails,
          naverPlaceUrl,
          reviewSignals);
    }

    private long reviewCautions() {
      return reviewSignals.stream()
          .filter(signal -> signal.verdict() == Verdict.NO || signal.verdict() == Verdict.CONFLICT)
          .count();
    }

    private long reviewSupport() {
      return reviewSignals.stream().filter(signal -> signal.verdict() == Verdict.YES).count();
    }

    @Override
    public String toString() {
      return "WebCandidate[content omitted]";
    }
  }

  public record Result(
      Mode mode,
      State state,
      Instant observedAt,
      Instant validUntil,
      List<Candidate> candidates,
      int excludedCandidates,
      FailureReason failureReason) {
    public Result(
        Mode mode,
        State state,
        Instant observedAt,
        Instant validUntil,
        List<Candidate> candidates,
        int excludedCandidates) {
      this(mode, state, observedAt, validUntil, candidates, excludedCandidates, null);
    }

    public Result {
      candidates = List.copyOf(candidates);
      if (candidates.size() > 3 || excludedCandidates < 0)
        throw new IllegalArgumentException("Invalid shortlist");
      if (failureReason != null && state != State.FAILED)
        throw new IllegalArgumentException("Failure reason requires FAILED state");
    }

    @Override
    public String toString() {
      return "WebResult[state=" + state + ", count=" + candidates.size() + "]";
    }
  }

  public record Saved(
      String id,
      Mode mode,
      Request request,
      Feedback feedback,
      Instant createdAt,
      boolean deleted) {}

  public record Response(String requestId, Result result) {}

  /**
   * Rank assessed places before truncation. Stable ties preserve the provider's proximity order.
   */
  static List<Candidate> shortlist(List<Candidate> candidates) {
    var ordered =
        candidates.stream()
            .sorted(
                Comparator.comparingInt(
                        (Candidate candidate) -> candidate.remainingQuestions().size())
                    .thenComparingLong(Candidate::reviewCautions)
                    .thenComparing(Comparator.comparingLong(Candidate::reviewSupport).reversed()))
            .limit(3)
            .toList();
    var ranked = new ArrayList<Candidate>();
    for (Candidate candidate : ordered) ranked.add(candidate.withOrdinal(ranked.size() + 1));
    return List.copyOf(ranked);
  }

  static Result empty(Mode mode, State state) {
    return new Result(mode, state, null, null, List.of(), 0);
  }

  static Result failed(Mode mode, FailureReason reason) {
    return new Result(mode, State.FAILED, null, null, List.of(), 0, reason);
  }

  static boolean text(String value, int max) {
    return value != null
        && !value.isBlank()
        && value.length() <= max
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  static boolean safeCitation(URI url) {
    if (url == null
        || !"https".equalsIgnoreCase(url.getScheme())
        || url.getHost() == null
        || url.toASCIIString().length() > 2048
        || url.getUserInfo() != null
        || url.getFragment() != null
        || (url.getPort() != -1 && url.getPort() != 443)) return false;
    String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
    return host.contains(".")
        && !host.matches("[0-9.]+")
        && !host.contains(":")
        && !host.endsWith(".localhost")
        && !host.endsWith(".local")
        && !host.endsWith(".internal");
  }
}
