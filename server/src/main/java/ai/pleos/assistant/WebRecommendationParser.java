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

/**
 * Validates cited model reports, not merchant identity or the truth of a webpage. Never fetches.
 */
public final class WebRecommendationParser {
  static final int MAX_WEB_SEARCHES = 3;
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
      JsonNode input = content.path("candidates");
      if (!input.isArray() || input.size() > 8) return empty(mode, State.FAILED);
      if (input.isEmpty() && supplemental.isEmpty())
        return new Result(mode, State.EMPTY, observedAt, validUntil, List.of(), 0);
      Map<String, Draft> distinct = new LinkedHashMap<>();
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
        String key =
            PlaceDetailEnricher.canonical(name) + "\u0000" + PlaceDetailEnricher.canonical(address);
        Draft draft =
            distinct.computeIfAbsent(key, ignored -> new Draft(name, address, identityUrl));
        switch (candidate.path("venueCategory").asText()) {
          case "RESTAURANT" -> draft.categories.add(VenueCategory.RESTAURANT);
          case "CAFE" -> draft.categories.add(VenueCategory.CAFE);
          default -> {} // Earlier results and unsupported classifications remain UNKNOWN.
        }
        String cuisine = candidate.path("cuisine").asText();
        if (UserRecommendationIntent.CUISINES.contains(cuisine)) draft.cuisines.add(cuisine);
        JsonNode businessDetails = candidate.path("businessDetails");
        if (businessDetails.isArray() && businessDetails.size() <= 6) {
          for (JsonNode detail : businessDetails) {
            try {
              shape(
                  detail,
                  Set.of("kind", "condition", "sourceUrl", "sourceType", "sourceUpdatedAt"));
              var kind = BusinessDetailKind.valueOf(required(detail, "kind", 30));
              var evidence = readEvidence(detail, citations, consulted, observedAt, validUntil);
              if (evidence != null && evidence.sourceType() != SourceType.VISITOR_REVIEW)
                draft.businessDetails.merge(
                    kind,
                    evidence,
                    (previous, current) ->
                        previous.sourceType() != SourceType.MERCHANT_PAGE
                                && current.sourceType() == SourceType.MERCHANT_PAGE
                            ? current
                            : previous);
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
            shape(
                report,
                Set.of(
                    "need", "answer", "condition", "sourceUrl", "sourceType", "sourceUpdatedAt"));
            Need need = Need.valueOf(required(report, "need", 40));
            Verdict answer = Verdict.valueOf(required(report, "answer", 20));
            if (answer == Verdict.CONFLICT || !request.requiredNeeds().contains(need)) continue;
            var evidence = readEvidence(report, citations, consulted, observedAt, validUntil);
            if (evidence == null) continue;
            boolean inlineCited = citations.containsKey(evidence.url());
            draft
                .reports
                .computeIfAbsent(need, ignored -> new ArrayList<>())
                .add(new Report(inlineCited ? answer : Verdict.UNKNOWN, evidence, answer));
          } catch (RuntimeException invalidReport) {
            // A malformed report cannot establish a feature; retain the place as UNKNOWN.
          }
        }
      }
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
    }
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

  private static Evidence readEvidence(
      JsonNode report,
      Map<URI, String> citations,
      Set<URI> consulted,
      Instant observedAt,
      Instant validUntil) {
    URI url = safeUri(report.path("sourceUrl"));
    if (url == null || (!citations.containsKey(url) && !consulted.contains(url))) return null;
    boolean inlineCited = citations.containsKey(url);
    SourceType type =
        inlineCited ? SourceType.valueOf(required(report, "sourceType", 30)) : SourceType.OTHER;
    String condition = report.path("condition").asText(null);
    if (!text(condition, 240)) return null;
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
