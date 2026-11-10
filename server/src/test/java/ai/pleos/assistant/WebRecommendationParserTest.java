package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebRecommendationParserTest {
  static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
  final ObjectMapper json = new ObjectMapper();
  final WebRecommendationParser parser = new WebRecommendationParser();
  final Request request =
      new Request(
          UUID.randomUUID(),
          1,
          "가상 서울숲",
          Set.of(Need.PET_FRIENDLY, Need.INDOOR_PET_FRIENDLY, Need.PARKING),
          false);

  ObjectNode response() throws Exception {
    return (ObjectNode) json.readTree(MockWebRecommendationGateway.fictionalResponse());
  }

  ObjectNode content(ObjectNode root) throws Exception {
    return (ObjectNode) json.readTree(root.at("/output/1/content/0/text").textValue());
  }

  Result parse(ObjectNode root, ObjectNode content) throws Exception {
    ((ObjectNode) root.at("/output/1/content/0"))
        .put("text", json.writerWithDefaultPrettyPrinter().writeValueAsString(content));
    return parser.parse(root.toString(), request, Mode.MOCK, NOW, NOW.plusSeconds(300));
  }

  ObjectNode report(ObjectNode content, int candidate, int report) {
    return (ObjectNode) content.at("/candidates/" + candidate + "/reports/" + report);
  }

  @Test
  void highChairAndStepFreeRequireTheirOwnEvidenceAndRemainPhoneQuestions() throws Exception {
    var needs =
        new Request(
            UUID.randomUUID(),
            1,
            "가상 서울숲",
            Set.of(Need.PARKING, Need.HIGH_CHAIR, Need.STEP_FREE_ACCESS),
            false);
    var result =
        parser.parse(
            MockWebRecommendationGateway.fictionalResponse(),
            needs,
            Mode.MOCK,
            NOW,
            NOW.plusSeconds(300));
    assertThat(result.state()).isEqualTo(State.RESULTS);
    assertThat(result.candidates().getFirst().remainingQuestions())
        .contains(Need.HIGH_CHAIR, Need.STEP_FREE_ACCESS);
    assertThat(result.candidates().getFirst().assessments())
        .filteredOn(a -> a.need() == Need.HIGH_CHAIR || a.need() == Need.STEP_FREE_ACCESS)
        .allMatch(a -> a.verdict() == Verdict.UNKNOWN);
    assertThat(result.candidates()).allMatch(c -> !c.bookable() && !c.inquiryDispatchAllowed());
  }

  @Test
  void maximumThreeWithQualifiedYesUnknownReviewAndExcludedNo() throws Exception {
    var root = response();
    var result = parse(root, content(root));
    assertThat(result.state()).isEqualTo(State.RESULTS);
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.excludedCandidates()).isEqualTo(1);
    assertThat(result.candidates().getFirst().fit()).isEqualTo(Fit.POTENTIAL_MATCH);
    assertThat(result.candidates().get(1).remainingQuestions())
        .containsExactly(Need.INDOOR_PET_FRIENDLY);
    assertThat(result.candidates().get(2).assessments())
        .allMatch(a -> a.verdict() == Verdict.UNKNOWN);
    assertThat(result.candidates())
        .allMatch(c -> !c.identityVerified() && !c.bookable() && !c.inquiryDispatchAllowed());
    assertThat(
            result.candidates().get(1).assessments().getFirst().evidence().getFirst().condition())
        .contains("테라스");
  }

  @Test
  void consulted_sources_without_inline_citations_can_identify_places_but_not_verify_conditions()
      throws Exception {
    var root = response();
    var message = (ObjectNode) root.at("/output/1/content/0");
    var annotations = (ArrayNode) message.path("annotations");
    var sources = json.createArrayNode();
    for (var annotation : annotations)
      sources.addObject().put("url", annotation.path("url").asText());
    ((ObjectNode) root.at("/output/0"))
        .putObject("action")
        .put("type", "search")
        .set("sources", sources);
    message.set("annotations", json.createArrayNode());
    ((ArrayNode) root.path("output"))
        .addObject()
        .put("type", "web_search_call")
        .put("status", "searching")
        .putObject("action")
        .put("type", "open_page");
    var result = parse(root, content(root));
    assertThat(result.state()).isEqualTo(State.RESULTS);
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.candidates())
        .allSatisfy(
            candidate -> {
              assertThat(candidate.fit()).isEqualTo(Fit.NEEDS_CONFIRMATION);
              assertThat(candidate.assessments())
                  .allSatisfy(
                      assessment -> assertThat(assessment.verdict()).isEqualTo(Verdict.UNKNOWN));
            });
    assertThat(result.candidates().getFirst().reviews()).hasSize(1);
    assertThat(result.candidates().getFirst().assessments())
        .anySatisfy(
            assessment ->
                assertThat(assessment.evidence().getFirst().condition()).contains("가상 안내"));
  }

  @Test
  void generalPetYesNeverFillsMissingIndoorPolicy() throws Exception {
    var root = response();
    var content = content(root);
    ((ArrayNode) content.at("/candidates/0/reports")).remove(Need.INDOOR_PET_FRIENDLY.ordinal());
    var result = parse(root, content);
    assertThat(
            result.candidates().stream()
                .filter(c -> c.name().endsWith("1"))
                .findFirst()
                .orElseThrow()
                .remainingQuestions())
        .contains(Need.INDOOR_PET_FRIENDLY);
  }

  @Test
  void contradictoryReportsAreConflictAndDoNotSilentlyChooseNewest() throws Exception {
    var root = response();
    var content = content(root);
    var reports = (ArrayNode) content.at("/candidates/0/reports");
    reports.add(
        report(content, 0, 0).deepCopy().put("answer", "NO").put("sourceType", "VISITOR_REVIEW"));
    var candidate =
        parse(root, content).candidates().stream()
            .filter(c -> c.name().endsWith("1"))
            .findFirst()
            .orElseThrow();
    assertThat(
            candidate.assessments().stream()
                .filter(a -> a.need() == Need.PET_FRIENDLY)
                .findFirst()
                .orElseThrow()
                .verdict())
        .isEqualTo(Verdict.CONFLICT);
    assertThat(candidate.remainingQuestions()).contains(Need.PET_FRIENDLY);
  }

  @Test
  void duplicateSameNameAndAddressMergeEvidenceButDifferentBranchesStaySeparate() throws Exception {
    var root = response();
    var content = content(root);
    var candidates = (ArrayNode) content.get("candidates");
    var duplicate = ((ObjectNode) candidates.get(0)).deepCopy();
    ((ObjectNode) duplicate.at("/reports/0")).put("answer", "NO");
    candidates.add(duplicate);
    var result = parse(root, content);
    assertThat(result.candidates()).hasSize(3);
    assertThat(
            result.candidates().stream()
                .filter(c -> c.name().endsWith("1"))
                .findFirst()
                .orElseThrow()
                .remainingQuestions())
        .contains(Need.PET_FRIENDLY);
    duplicate.put("address", "다른 가상 지점 주소 99");
    assertThat(parse(root, content).excludedCandidates()).isEqualTo(2);
  }

  @Test
  void staleFutureAndUncitedReportsStayUnknown() throws Exception {
    for (String date : new String[] {"2026-11-11T00:00:00Z", "2025-01-01T00:00:00Z"}) {
      var root = response();
      var content = content(root);
      report(content, 0, 0).put("sourceUpdatedAt", date);
      assertThat(
              parse(root, content).candidates().stream()
                  .filter(c -> c.name().endsWith("1"))
                  .findFirst()
                  .orElseThrow()
                  .remainingQuestions())
          .contains(Need.PET_FRIENDLY);
    }
    var root = response();
    var content = content(root);
    report(content, 0, 0).put("sourceUrl", "https://unseen.example/source");
    assertThat(
            parse(root, content).candidates().stream()
                .filter(c -> c.name().endsWith("1"))
                .findFirst()
                .orElseThrow()
                .remainingQuestions())
        .contains(Need.PET_FRIENDLY);
  }

  @Test
  void uncitedIdentityIsSkippedAndMalformedReportsRemainUnknown() throws Exception {
    var root = response();
    var content = content(root);
    ((ObjectNode) content.at("/candidates/0"))
        .put("identitySourceUrl", "https://unseen.example/source");
    assertThat(parse(root, content).candidates()).hasSize(2);
    for (var candidate : content.get("candidates"))
      ((ObjectNode) candidate).put("identitySourceUrl", "https://unseen.example/source");
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
    root = response();
    content = content(root);
    ((ObjectNode) content.at("/candidates/0")).put("phone", "fictional-private-contact");
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
    root = response();
    content = content(root);
    report(content, 0, 0).put("answer", true);
    assertThat(parse(root, content).state()).isEqualTo(State.RESULTS);
    assertThat(
            parse(root, content).candidates().stream()
                .filter(c -> c.name().endsWith("1"))
                .findFirst()
                .orElseThrow()
                .remainingQuestions())
        .contains(Need.PET_FRIENDLY);
  }

  @Test
  void placeholderOrAreaOnlyAddressCannotBecomeAPlaceRecommendation() throws Exception {
    var root = response();
    var content = content(root);
    ((ObjectNode) content.at("/candidates/0")).put("address", "UNKNOWN");
    assertThat(parse(root, content).state()).isEqualTo(State.RESULTS);
    assertThat(parse(root, content).candidates()).hasSize(2);
    for (var candidate : content.get("candidates"))
      ((ObjectNode) candidate).put("address", "서울숲 근처");
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
  }

  @Test
  void emptyFilteredFailedAndMalformedResponsesRemainDistinct() throws Exception {
    var root = response();
    var content = content(root);
    content.set("candidates", json.createArrayNode());
    assertThat(parse(root, content).state()).isEqualTo(State.EMPTY);
    root = response();
    content = content(root);
    for (var candidate : content.get("candidates"))
      for (var report : candidate.get("reports"))
        ((ObjectNode) report).put("sourceType", "MERCHANT_PAGE").put("answer", "NO");
    assertThat(parse(root, content).state()).isEqualTo(State.FILTERED);
    root.put("status", "incomplete");
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
    for (String invalid :
        new String[] {
          "{", "{} {}", "{\"status\":\"completed\",\"status\":\"completed\"}", "x".repeat(1_048_577)
        })
      assertThat(parser.parse(invalid, request, Mode.MOCK, NOW, NOW.plusSeconds(300)).state())
          .isEqualTo(State.FAILED);
  }

  @Test
  void absentOrExcessiveWebActionsCannotPassAsGroundedResults() throws Exception {
    var root = response();
    var content = content(root);
    ((ObjectNode) root.at("/output/0")).put("type", "unrelated");
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
    root = response();
    content = content(root);
    for (int i = 0; i < 3; i++)
      ((ArrayNode) root.get("output")).add(root.at("/output/0").deepCopy());
    assertThat(parse(root, content).state()).isEqualTo(State.FAILED);
  }
}
