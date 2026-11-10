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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates cited model reports, not merchant identity or the truth of a webpage. Never fetches.
 */
public final class WebRecommendationParser {
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
    try {
      if (response == null || response.length() > 1_048_576 || !validUntil.isAfter(observedAt))
        return empty(mode, State.FAILED);
      JsonNode root = json.readTree(response);
      if (!"completed".equals(root.path("status").asText()) || !root.path("output").isArray())
        return empty(mode, State.FAILED);
      int searches = 0;
      Map<URI, String> citations = new HashMap<>();
      var texts = new ArrayList<String>();
      for (JsonNode output : root.path("output")) {
        if ("web_search_call".equals(output.path("type").asText())) {
          if (!"completed".equals(output.path("status").asText())) return empty(mode, State.FAILED);
          searches++;
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
            URI url = URI.create(required(annotation, "url", 2048));
            if (safeCitation(url)) citations.put(url, required(annotation, "title", 200));
            if (citations.size() > 64) return empty(mode, State.FAILED);
          }
        }
      }
      if (searches != 1 || texts.size() != 1) return empty(mode, State.FAILED);
      JsonNode content = json.readTree(texts.getFirst());
      shape(content, Set.of("candidates"));
      JsonNode input = content.path("candidates");
      if (!input.isArray() || input.size() > 8) return empty(mode, State.FAILED);
      if (input.isEmpty())
        return new Result(mode, State.EMPTY, observedAt, validUntil, List.of(), 0);
      Map<String, Draft> distinct = new LinkedHashMap<>();
      for (JsonNode candidate : input) {
        shape(candidate, Set.of("name", "address", "identitySourceUrl", "reports"));
        String name = required(candidate, "name", 150),
            address = required(candidate, "address", 250);
        URI identityUrl = URI.create(required(candidate, "identitySourceUrl", 2048));
        if (!citations.containsKey(identityUrl)) return empty(mode, State.FAILED);
        String key =
            PlaceDetailEnricher.canonical(name) + "\u0000" + PlaceDetailEnricher.canonical(address);
        Draft draft =
            distinct.computeIfAbsent(key, ignored -> new Draft(name, address, identityUrl));
        JsonNode reports = candidate.path("reports");
        if (!reports.isArray() || reports.size() > 32) return empty(mode, State.FAILED);
        for (JsonNode report : reports) {
          shape(
              report,
              Set.of("need", "answer", "condition", "sourceUrl", "sourceType", "sourceUpdatedAt"));
          Need need = Need.valueOf(required(report, "need", 40));
          Verdict answer = Verdict.valueOf(required(report, "answer", 20));
          if (answer == Verdict.CONFLICT) return empty(mode, State.FAILED);
          if (!request.requiredNeeds().contains(need)) continue;
          URI url = URI.create(required(report, "sourceUrl", 2048));
          if (!citations.containsKey(url)) continue;
          SourceType type = SourceType.valueOf(required(report, "sourceType", 30));
          Instant updated =
              report.path("sourceUpdatedAt").isNull()
                  ? null
                  : Instant.parse(required(report, "sourceUpdatedAt", 40));
          if (updated != null
              && (updated.isAfter(observedAt)
                  || updated.isBefore(observedAt.minus(Duration.ofDays(180))))) continue;
          var evidence =
              new Evidence(
                  url,
                  citations.get(url),
                  type,
                  observedAt,
                  updated,
                  validUntil,
                  required(report, "condition", 240),
                  "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
                  Model.Scope.GENERAL,
                  null);
          draft
              .reports
              .computeIfAbsent(need, ignored -> new ArrayList<>())
              .add(new Report(answer, evidence));
        }
      }
      var ranked = new ArrayList<Candidate>();
      int excluded = 0;
      for (Draft draft : distinct.values()) {
        var assessments = new ArrayList<Assessment>();
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
        }
        if (no) {
          excluded++;
          continue;
        }
        ranked.add(
            new Candidate(
                0,
                draft.name,
                draft.address,
                draft.identityUrl,
                remaining.isEmpty() ? Fit.POTENTIAL_MATCH : Fit.NEEDS_CONFIRMATION,
                assessments,
                remaining,
                false,
                false,
                false));
      }
      ranked.sort(
          Comparator.comparingInt((Candidate c) -> c.remainingQuestions().size())
              .thenComparing(Candidate::name)
              .thenComparing(Candidate::address));
      var shortlist = new ArrayList<Candidate>();
      for (Candidate c : ranked.stream().limit(3).toList())
        shortlist.add(
            new Candidate(
                shortlist.size() + 1,
                c.name(),
                c.address(),
                c.identitySourceUrl(),
                c.fit(),
                c.assessments(),
                c.remainingQuestions(),
                false,
                false,
                false));
      return new Result(
          mode,
          shortlist.isEmpty() ? State.FILTERED : State.RESULTS,
          observedAt,
          validUntil,
          shortlist,
          excluded);
    } catch (Exception invalid) {
      return empty(mode, State.FAILED);
    }
  }

  private record Report(Verdict answer, Evidence evidence) {}

  private static class Draft {
    final String name, address;
    final URI identityUrl;
    final Map<Need, List<Report>> reports = new EnumMap<>(Need.class);

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
