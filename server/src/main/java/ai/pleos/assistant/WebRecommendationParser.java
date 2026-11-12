package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates cited model reports, not merchant identity or the truth of a webpage. Never fetches.
 */
public final class WebRecommendationParser {
  static final int MAX_WEB_SEARCHES = 3;
  private static final Logger LOG = LoggerFactory.getLogger(WebRecommendationParser.class);
  private static final java.util.regex.Pattern ROAD_ADDRESS =
      java.util.regex.Pattern.compile("(?:^|\\s)(\\S+(?:로|길))\\s+(\\d+(?:-\\d+)?)(?=[\\s,]|$)");

  private final ObjectMapper json =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(24)
                          .maxStringLength(262144)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public Result parse(
      String response, Request request, Mode mode, Instant observedAt, Instant validUntil) {
    return parse(response, request, mode, observedAt, validUntil, List.of());
  }

  Result parse(
      String response,
      Request request,
      Mode mode,
      Instant observedAt,
      Instant validUntil,
      List<Candidate> supplemental) {
    return parse(response, request, mode, observedAt, validUntil, supplemental, List.of());
  }

  Result parse(
      String response,
      Request request,
      Mode mode,
      Instant observedAt,
      Instant validUntil,
      List<Candidate> supplemental,
      List<Candidate> nearby) {
    return parse(response, request, mode, observedAt, validUntil, supplemental, nearby, null);
  }

  Result parse(
      String response,
      Request request,
      Mode mode,
      Instant observedAt,
      Instant validUntil,
      List<Candidate> supplemental,
      List<Candidate> nearby,
      java.util.function.Function<JsonNode, Set<String>> verifyBranches) {
    FailureReason stage = FailureReason.INVALID_RESPONSE;
    try {
      if (response == null || response.length() > 1_048_576 || !validUntil.isAfter(observedAt))
        return empty(mode, State.FAILED);
      JsonNode root = json.readTree(response);
      if (!"completed".equals(root.path("status").asText()) || !root.path("output").isArray())
        return empty(mode, State.FAILED);
      int searches = 0;
      Map<URI, String> citations = new HashMap<>();
      Set<URI> consulted = new HashSet<>();
      var texts = new ArrayList<String>();
      stage = FailureReason.INVALID_SOURCE_METADATA;
      for (JsonNode output : root.path("output")) {
        if ("web_search_call".equals(output.path("type").asText())) {
          if (!"search".equals(output.path("action").path("type").asText())
              || !"completed".equals(output.path("status").asText())) continue;
          if (++searches > MAX_WEB_SEARCHES) return empty(mode, State.FAILED);
          for (JsonNode source : output.path("action").path("sources")) {
            URI url = safeUri(source.path("url"));
            if (url != null) consulted.add(url);
            if (consulted.size() > MAX_WEB_SEARCHES * 64) return empty(mode, State.FAILED);
          }
        }
        if (!"message".equals(output.path("type").asText())
            || !"assistant".equals(output.path("role").asText())) continue;
        for (JsonNode content : output.path("content")) {
          if (!"output_text".equals(content.path("type").asText())) continue;
          JsonNode text = content.path("text");
          if (!text.isTextual() || text.textValue().isBlank() || text.textValue().length() > 262144)
            return empty(mode, State.FAILED);
          texts.add(text.textValue());
          for (JsonNode annotation : content.path("annotations")) {
            if (!"url_citation".equals(annotation.path("type").asText())) continue;
            URI url = safeUri(annotation.path("url"));
            String title = annotation.path("title").asText(null);
            if (url != null && text(title, 200)) citations.put(url, title.strip());
            if (citations.size() > 64) return empty(mode, State.FAILED);
          }
        }
      }
      // One Responses request may contain search plus page-open actions. It is still one API call.
      if (searches < 1 || texts.size() != 1) return empty(mode, State.FAILED);
      JsonNode content = json.readTree(texts.getFirst());
      stage = FailureReason.INVALID_OUTPUT_SHAPE;
      shape(content, Set.of("candidates"));
      var branches = content.path("candidates");
      if (!branches.isArray() || branches.size() > 8) return empty(mode, State.FAILED);
      var verified = verifyBranches == null ? null : verifyBranches.apply(branches);
      return parseCandidates(
          content,
          request,
          mode,
          observedAt,
          validUntil,
          citations,
          consulted,
          supplemental,
          nearby,
          verified,
          null);
    } catch (Exception invalid) {
      return failed(mode, stage);
    }
  }

  /** The loopback lookup service supplies observed sources, without an AI response envelope. */
  Result parsePlaceLookup(String response, Request request, Instant now) {
    Mode mode = Mode.WEB_SEARCH;
    try {
      if (response == null || response.length() > 1_048_576)
        return failed(mode, FailureReason.INVALID_RESPONSE);
      var root = json.readTree(response);
      shape(root, Set.of("observedAt", "sources", "candidates"));
      Instant observedAt = Instant.parse(required(root, "observedAt", 40));
      Instant validUntil = observedAt.plusSeconds(300);
      if (observedAt.isAfter(now) || !validUntil.isAfter(now))
        return failed(mode, FailureReason.INVALID_SOURCE_METADATA);
      JsonNode sources = root.path("sources");
      if (!sources.isArray() || sources.size() > 64)
        return failed(mode, FailureReason.INVALID_SOURCE_METADATA);
      var citations = new HashMap<URI, String>();
      for (JsonNode source : sources) {
        shape(source, Set.of("url", "title"));
        URI url = safeUri(source.path("url"));
        if (url == null || citations.putIfAbsent(url, required(source, "title", 200)) != null)
          return failed(mode, FailureReason.INVALID_SOURCE_METADATA);
      }
      return parseCandidates(
          root,
          request,
          mode,
          observedAt,
          validUntil,
          citations,
          Set.of(),
          List.of(),
          List.of(),
          null,
          now);
    } catch (Exception invalid) {
      return failed(mode, FailureReason.INVALID_RESPONSE);
    }
  }

  private Result parseCandidates(
      JsonNode content,
      Request request,
      Mode mode,
      Instant observedAt,
      Instant validUntil,
      Map<URI, String> citations,
      Set<URI> consulted,
      List<Candidate> supplemental,
      List<Candidate> nearby,
      Set<String> verified,
      Instant lookupNow) {
    FailureReason stage = FailureReason.INVALID_OUTPUT_SHAPE;
    var diagnostic = nearby.isEmpty() ? null : new DetailDiagnostics(content.path("candidates"));
    try {
      JsonNode input = content.path("candidates");
      if (!input.isArray() || input.size() > 8) return empty(mode, State.FAILED);
      if (input.isEmpty() && supplemental.isEmpty() && nearby.isEmpty())
        return new Result(mode, State.EMPTY, observedAt, validUntil, List.of(), 0);
      Map<String, Draft> distinct = new LinkedHashMap<>();
      for (Candidate place : nearby) {
        var draft = new Draft(place.name(), place.address(), place.identitySourceUrl());
        draft.categories.add(place.venueCategory());
        draft.businessDetails.putAll(place.businessDetails());
        Evidence cuisine = place.businessDetails().get(BusinessDetailKind.CUISINE);
        if (cuisine != null) draft.cuisines.add(cuisine.condition());
        attachConditions(draft, place);
        distinct.put(identityKey(place.name(), place.address()), draft);
      }
      boolean matchedNearby = false;
      for (JsonNode candidate : input) {
        stage = FailureReason.INVALID_CANDIDATE;
        boolean hasReviews = candidate.has("reviews");
        var fields = new HashSet<>(Set.of("name", "address", "identitySourceUrl", "reports"));
        if (hasReviews) fields.add("reviews");
        if (candidate.has("venueCategory")) fields.add("venueCategory");
        if (candidate.has("cuisine")) fields.add("cuisine");
        if (candidate.has("businessDetails")) fields.add("businessDetails");
        shape(candidate, fields);
        String rawName = candidate.path("name").asText(null),
            rawAddress = candidate.path("address").asText(null);
        if (!validIdentityText(rawName, 150)
            || !validIdentityText(rawAddress, 250)
            || rawAddress.codePoints().noneMatch(Character::isDigit)) continue;
        String name = rawName.strip(), address = rawAddress.strip();
        URI identityUrl = safeUri(candidate.path("identitySourceUrl"));
        if (identityUrl == null) continue;
        if (!citations.containsKey(identityUrl) && !consulted.contains(identityUrl)) continue;
        String key = identityKey(name, address);
        if (verified != null && !verified.contains(key)) continue;
        if (!nearby.isEmpty()) {
          if (!distinct.containsKey(key)) continue;
          matchedNearby = true;
          diagnostic.matchedBranches++;
        }
        Draft draft =
            distinct.computeIfAbsent(key, ignored -> new Draft(name, address, identityUrl));
        if (nearby.isEmpty()) {
          switch (candidate.path("venueCategory").asText()) {
            case "RESTAURANT" -> draft.categories.add(VenueCategory.RESTAURANT);
            case "CAFE" -> draft.categories.add(VenueCategory.CAFE);
            default -> {} // Unsupported classifications remain UNKNOWN.
          }
          String cuisine = candidate.path("cuisine").asText();
          if (UserRecommendationIntent.CUISINES.contains(cuisine)) draft.cuisines.add(cuisine);
        }
        JsonNode businessDetails = candidate.path("businessDetails");
        if (businessDetails.isArray() && businessDetails.size() <= 6) {
          for (JsonNode detail : businessDetails) {
            try {
              evidenceShape(
                  detail,
                  Set.of("kind", "condition", "sourceUrl", "sourceType", "sourceUpdatedAt"),
                  lookupNow);
              var kind = BusinessDetailKind.valueOf(required(detail, "kind", 30));
              if (!nearby.isEmpty() && kind == BusinessDetailKind.CUISINE) continue;
              var evidence =
                  readEvidence(detail, citations, consulted, observedAt, validUntil, lookupNow);
              if (kind == BusinessDetailKind.REVIEW_COUNT
                  && (verified == null
                      || evidence == null
                      || evidence.sourceUpdatedAt() == null
                      || !citations.containsKey(evidence.url())
                      || visitorReviewCount(evidence.condition()) < 0)) continue;
              if (evidence != null && evidence.sourceType() != SourceType.VISITOR_REVIEW) {
                if (diagnostic != null) diagnostic.accept(kind.name());
                draft.businessDetails.merge(
                    kind,
                    evidence,
                    (previous, current) ->
                        previous.sourceType() != SourceType.MERCHANT_PAGE
                                && current.sourceType() == SourceType.MERCHANT_PAGE
                            ? current
                            : previous);
              }
            } catch (RuntimeException invalidDetail) {
              // Optional details without usable evidence do not invalidate the branch.
            }
          }
        }
        if (hasReviews) {
          stage = FailureReason.INVALID_REVIEW;
          JsonNode reviews = candidate.path("reviews");
          if (!reviews.isArray() || reviews.size() > 3) return empty(mode, State.FAILED);
          for (JsonNode review : reviews) {
            shape(review, Set.of("summary", "sourceUrl"));
            URI url = safeUri(review.path("sourceUrl"));
            if (url == null) continue;
            if (!citations.containsKey(url) && !consulted.contains(url)) continue;
            String summary = review.path("summary").asText(null);
            if (!text(summary, 240)) continue;
            draft.reviews.add(
                new Evidence(
                    url,
                    citations.getOrDefault(url, "웹검색 출처"),
                    SourceType.VISITOR_REVIEW,
                    observedAt,
                    null,
                    validUntil,
                    summary.strip(),
                    "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
                    Model.Scope.GENERAL,
                    null));
          }
        }
        stage = FailureReason.INVALID_REPORT;
        JsonNode reports = candidate.path("reports");
        if (!reports.isArray() || reports.size() > 32) return empty(mode, State.FAILED);
        for (JsonNode report : reports) {
          try {
            evidenceShape(
                report,
                Set.of("need", "answer", "condition", "sourceUrl", "sourceType", "sourceUpdatedAt"),
                lookupNow);
            Need need = Need.valueOf(required(report, "need", 40));
            Verdict answer = Verdict.valueOf(required(report, "answer", 20));
            if (answer == Verdict.CONFLICT || !request.requiredNeeds().contains(need)) continue;
            var evidence =
                readEvidence(report, citations, consulted, observedAt, validUntil, lookupNow);
            if (evidence == null) continue;
            boolean inlineCited = citations.containsKey(evidence.url());
            if (diagnostic != null
                && inlineCited
                && evidence.sourceType() == SourceType.MERCHANT_PAGE
                && (answer == Verdict.YES || answer == Verdict.NO)) diagnostic.accept(need.name());
            draft
                .reports
                .computeIfAbsent(need, ignored -> new ArrayList<>())
                .add(new Report(inlineCited ? answer : Verdict.UNKNOWN, evidence, answer));
          } catch (RuntimeException invalidReport) {
            // A malformed report cannot establish a feature; retain the place as UNKNOWN.
          }
        }
      }
      if (!nearby.isEmpty() && !input.isEmpty() && !matchedNearby)
        return failed(mode, FailureReason.UNMATCHED_IDENTITY_SOURCE);
      if (verified != null && !input.isEmpty() && distinct.isEmpty())
        return failed(mode, FailureReason.UNMATCHED_IDENTITY_SOURCE);
      if (distinct.isEmpty() && !input.isEmpty()) return empty(mode, State.FAILED);
      stage = FailureReason.INVALID_CANDIDATE;
      // Only attach verified facts to a branch identified by both its name and its street address.
      for (Candidate supplement : supplemental) {
        distinct.values().stream()
            .filter(draft -> sameAddress(draft, supplement) && sameName(draft, supplement))
            .forEach(draft -> attachConditions(draft, supplement));
      }
      var candidates = new ArrayList<Candidate>();
      int excluded = 0;
      for (Draft draft : distinct.values()) {
        if (ArrivalHours.closedAt(request.arrivalAt(), draft.businessDetails)) {
          excluded++;
          continue;
        }
        var category =
            draft.categories.size() == 1
                ? draft.categories.iterator().next()
                : VenueCategory.UNKNOWN;
        if (request.venueCategory() != null && request.venueCategory() != category) {
          excluded++;
          continue;
        }
        if (!request.preferredCuisines().isEmpty()
            && (draft.cuisines.size() != 1
                || !request.preferredCuisines().contains(draft.cuisines.iterator().next())
                || !draft.businessDetails.containsKey(BusinessDetailKind.CUISINE))) {
          excluded++;
          continue;
        }
        var assessments = new ArrayList<Assessment>();
        var reviewSignals = new ArrayList<Assessment>();
        var remaining = new ArrayList<Need>();
        boolean no = false;
        for (Need need : request.requiredNeeds().stream().sorted().toList()) {
          List<Report> reports = draft.reports.getOrDefault(need, List.of());
          boolean yes = reports.stream().anyMatch(r -> r.answer == Verdict.YES);
          boolean negative = reports.stream().anyMatch(r -> r.answer == Verdict.NO);
          boolean merchant =
              reports.stream()
                  .anyMatch(
                      r ->
                          r.evidence.sourceType() == SourceType.MERCHANT_PAGE
                              && (r.answer == Verdict.YES || r.answer == Verdict.NO));
          Verdict value =
              yes && negative
                  ? Verdict.CONFLICT
                  : !merchant
                      ? Verdict.UNKNOWN
                      : yes ? Verdict.YES : negative ? Verdict.NO : Verdict.UNKNOWN;
          no |= value == Verdict.NO;
          if (value != Verdict.YES) remaining.add(need);
          assessments.add(
              new Assessment(
                  need, value, reports.stream().map(Report::evidence).distinct().toList()));
          var visitorReports =
              reports.stream()
                  .filter(
                      report ->
                          report.evidence.sourceType() == SourceType.VISITOR_REVIEW
                              && (report.answer == Verdict.YES || report.answer == Verdict.NO))
                  .toList();
          if (!visitorReports.isEmpty()) {
            boolean positive =
                visitorReports.stream().anyMatch(report -> report.answer == Verdict.YES);
            boolean caution =
                visitorReports.stream().anyMatch(report -> report.answer == Verdict.NO);
            reviewSignals.add(
                new Assessment(
                    need,
                    positive && caution ? Verdict.CONFLICT : positive ? Verdict.YES : Verdict.NO,
                    visitorReports.stream().map(Report::evidence).distinct().toList()));
          }
        }
        if (no) {
          excluded++;
          continue;
        }
        candidates.add(
            new Candidate(
                candidates.size() + 1,
                draft.name,
                draft.address,
                draft.identityUrl,
                category,
                remaining.isEmpty() ? Fit.POTENTIAL_MATCH : Fit.NEEDS_CONFIRMATION,
                assessments,
                draft.reviews.stream().distinct().limit(3).toList(),
                remaining,
                false,
                false,
                false,
                draft.businessDetails,
                null,
                reviewSignals));
      }
      // Compare against every sourced branch, including ones excluded by mandatory conditions.
      for (Candidate supplement : supplemental) {
        if (distinct.values().stream()
            .noneMatch(draft -> sameName(draft, supplement) || sameAddress(draft, supplement)))
          candidates.add(supplement);
      }
      var shortlist = shortlist(candidates);
      return new Result(
          mode,
          shortlist.isEmpty() ? State.FILTERED : State.RESULTS,
          observedAt,
          validUntil,
          shortlist,
          excluded);
    } catch (Exception invalid) {
      return failed(mode, stage);
    } finally {
      if (diagnostic != null) diagnostic.log();
    }
  }

  static String identityKey(String name, String address) {
    return PlaceDetailEnricher.canonical(name) + "\u0000" + PlaceDetailEnricher.canonical(address);
  }

  static String roadAddressForSearch(String area) {
    String normalized =
        java.text.Normalizer.normalize(area, java.text.Normalizer.Form.NFKC)
            .strip()
            .replaceAll("\\s+", " ");
    var match = ROAD_ADDRESS.matcher(normalized);
    if (!match.find()) throw new IllegalArgumentException("A road-name address is required");
    return normalized.substring(0, match.end());
  }

  private static boolean sameName(Draft draft, Candidate candidate) {
    return PlaceDetailEnricher.canonical(draft.name)
            .equals(PlaceDetailEnricher.canonical(candidate.name()))
        || SeongsuWesternCatalog.isListingAlias(draft.name, candidate.name());
  }

  private static boolean sameAddress(Draft draft, Candidate candidate) {
    var left = ROAD_ADDRESS.matcher(draft.address);
    var right = ROAD_ADDRESS.matcher(candidate.address());
    return left.find()
        && right.find()
        && left.group(1).equals(right.group(1))
        && left.group(2).equals(right.group(2));
  }

  private static void attachConditions(Draft draft, Candidate supplement) {
    for (Assessment assessment : supplement.assessments()) {
      var reports = draft.reports.computeIfAbsent(assessment.need(), ignored -> new ArrayList<>());
      if (reports.stream().anyMatch(report -> report.reportedAnswer == Verdict.NO)) continue;
      if (assessment.verdict() == Verdict.UNKNOWN && !reports.isEmpty()) continue;
      for (Evidence evidence : assessment.evidence())
        reports.add(new Report(assessment.verdict(), evidence, assessment.verdict()));
    }
  }

  private static void evidenceShape(JsonNode evidence, Set<String> fields, Instant lookupNow) {
    if (lookupNow != null
        && (evidence.has("evidenceObservedAt") || evidence.has("evidenceValidUntil"))) {
      var verified = new HashSet<>(fields);
      verified.add("evidenceObservedAt");
      verified.add("evidenceValidUntil");
      shape(evidence, verified);
      return;
    }
    shape(evidence, fields);
  }

  private static Evidence readEvidence(
      JsonNode report,
      Map<URI, String> citations,
      Set<URI> consulted,
      Instant observedAt,
      Instant validUntil,
      Instant lookupNow) {
    URI url = safeUri(report.path("sourceUrl"));
    if (url == null || (!citations.containsKey(url) && !consulted.contains(url))) return null;
    boolean inlineCited = citations.containsKey(url);
    SourceType type =
        inlineCited ? SourceType.valueOf(required(report, "sourceType", 30)) : SourceType.OTHER;
    String condition = report.path("condition").asText(null);
    if (!text(condition, 240)) return null;
    if (lookupNow != null && report.has("evidenceObservedAt")) {
      Instant checked = Instant.parse(required(report, "evidenceObservedAt", 40));
      Instant expires = Instant.parse(required(report, "evidenceValidUntil", 40));
      if (checked.isAfter(observedAt)
          || !expires.isAfter(lookupNow)
          || expires.isAfter(checked.plus(Duration.ofDays(7)))) return null;
      observedAt = checked;
      if (expires.isBefore(validUntil)) validUntil = expires;
    }
    Instant updated = null;
    if (!report.path("sourceUpdatedAt").isNull()) {
      String date = report.path("sourceUpdatedAt").asText(null);
      if (date == null || date.length() > 40) return null;
      updated = Instant.parse(date);
    }
    if (updated != null
        && (updated.isAfter(observedAt)
            || updated.isBefore(observedAt.minus(Duration.ofDays(180))))) return null;
    return new Evidence(
        url,
        inlineCited ? citations.get(url) : "웹검색 출처",
        type,
        observedAt,
        inlineCited ? updated : null,
        validUntil,
        condition.strip(),
        "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
        Model.Scope.GENERAL,
        null);
  }

  private record Report(Verdict answer, Evidence evidence, Verdict reportedAnswer) {}

  private static final class DetailDiagnostics {
    private final Map<String, Integer> received = counts();
    private final Map<String, Integer> accepted = counts();
    private final int branches;
    private int matchedBranches;

    DetailDiagnostics(JsonNode candidates) {
      branches = candidates.isArray() ? candidates.size() : 0;
      if (branches > 8) return;
      for (JsonNode candidate : candidates) {
        for (JsonNode detail : candidate.path("businessDetails")) {
          String kind = detail.path("kind").asText();
          if (!"PARKING".equals(kind)) received.computeIfPresent(kind, (key, count) -> count + 1);
        }
        for (JsonNode report : candidate.path("reports")) {
          if ("PARKING".equals(report.path("need").asText()))
            received.computeIfPresent("PARKING", (key, count) -> count + 1);
        }
      }
    }

    private static Map<String, Integer> counts() {
      var counts = new LinkedHashMap<String, Integer>();
      for (String kind : List.of("OPENING_HOURS", "BREAK_TIME", "PARKING")) counts.put(kind, 0);
      return counts;
    }

    void accept(String kind) {
      accepted.computeIfPresent(kind, (key, count) -> count + 1);
    }

    void log() {
      LOG.info(
          "Address enrichment validation: branches={}, matched={}, received={}, accepted={}",
          branches,
          matchedBranches,
          received,
          accepted);
    }
  }

  private static class Draft {
    final String name, address;
    final URI identityUrl;
    final Set<VenueCategory> categories = EnumSet.noneOf(VenueCategory.class);
    final Set<String> cuisines = new HashSet<>();
    final Map<BusinessDetailKind, Evidence> businessDetails =
        new EnumMap<>(BusinessDetailKind.class);
    final Map<Need, List<Report>> reports = new EnumMap<>(Need.class);
    final List<Evidence> reviews = new ArrayList<>();

    Draft(String name, String address, URI identityUrl) {
      this.name = name;
      this.address = address;
      this.identityUrl = identityUrl;
    }
  }

  private static String required(JsonNode node, String field, int length) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || !text(value.textValue(), length))
      throw new IllegalArgumentException("Invalid web response shape");
    return value.textValue().strip();
  }

  private static URI safeUri(JsonNode value) {
    if (!value.isTextual() || !text(value.textValue(), 2048)) return null;
    try {
      URI url = URI.create(value.textValue().strip());
      return safeCitation(url) ? url : null;
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static boolean validIdentityText(String value, int maxLength) {
    if (!text(value, maxLength)) return false;
    String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
    return !Set.of("UNKNOWN", "N/A", "NA", "TBD", "NULL", "미확인", "알 수 없음", "주소 미확인")
        .contains(normalized);
  }

  private static void shape(JsonNode node, Set<String> fields) {
    if (!node.isObject() || node.size() != fields.size())
      throw new IllegalArgumentException("Invalid web response shape");
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!fields.contains(name))
                throw new IllegalArgumentException("Invalid web response shape");
            });
  }
}
