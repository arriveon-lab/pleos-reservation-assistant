package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.List;
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
  void cuisineRequiresMatchingClassificationAndCitedMenuCategoryEvidence() throws Exception {
    var root = response();
    var body = content(root);
    for (int index = 0; index < 4; index++) {
      var candidate = (ObjectNode) body.at("/candidates/" + index);
      candidate.put("cuisine", index == 1 ? "한식" : "양식");
      candidate
          .putArray("businessDetails")
          .addObject()
          .put("kind", "CUISINE")
          .put("condition", index == 1 ? "한식 메뉴 안내" : "양식: 파스타 메뉴 안내")
          .put(
              "sourceUrl",
              index == 2
                  ? "https://uncited.example/fictional"
                  : candidate.path("identitySourceUrl").asText())
          .put("sourceType", "MERCHANT_PAGE")
          .putNull("sourceUpdatedAt");
    }
    ((ObjectNode) root.at("/output/1/content/0")).put("text", body.toString());
    var foodRequest =
        new Request(
            UUID.randomUUID(),
            1,
            "가상 지역",
            Set.of(Need.PARKING, Need.HIGH_CHAIR),
            false,
            null,
            Set.of("양식"));
    var result = parser.parse(root.toString(), foodRequest, Mode.MOCK, NOW, NOW.plusSeconds(300));
    assertThat(result.candidates()).extracting(Candidate::name).containsExactly("가상 웹추천 식당 1");
    assertThat(result.excludedCandidates()).isEqualTo(3);
    assertThat(result.candidates().getFirst().remainingQuestions()).contains(Need.HIGH_CHAIR);
    assertThat(result.candidates().getFirst().businessDetails())
        .containsKey(BusinessDetailKind.CUISINE);

    report(body, 0, Need.PARKING.ordinal()).put("answer", "NO");
    ((ObjectNode) root.at("/output/1/content/0")).put("text", body.toString());
    var rejected = parser.parse(root.toString(), foodRequest, Mode.MOCK, NOW, NOW.plusSeconds(300));
    assertThat(rejected.state()).isEqualTo(State.FILTERED);
    assertThat(rejected.candidates()).isEmpty();
  }

  @Test
  void expandedSearchKeepsThreeCitedCuisineMatchesWithUnconfirmedNeeds() throws Exception {
    var root = response();
    var body = content(root);
    var output = (ArrayNode) root.path("output");
    var firstSearch = (ObjectNode) output.get(0);
    for (int pass = 0; pass < 3; pass++) {
      var search = pass == 0 ? firstSearch : firstSearch.deepCopy();
      var sources = ((ObjectNode) search.path("action")).putArray("sources");
      for (int i = 0; i < 30; i++) {
        sources.addObject().put("url", "https://source.example/result-" + pass + "-" + i);
      }
      if (pass > 0) output.add(search);
    }
    for (var item : body.path("candidates")) {
      var candidate = (ObjectNode) item;
      candidate.put("cuisine", "양식");
      candidate
          .putArray("businessDetails")
          .addObject()
          .put("kind", "CUISINE")
          .put("condition", "가상 양식 메뉴")
          .put("sourceUrl", candidate.path("identitySourceUrl").asText())
          .put("sourceType", "MERCHANT_PAGE")
          .putNull("sourceUpdatedAt");
    }
    ((ObjectNode) output.get(1).at("/content/0")).put("text", body.toString());
    var foodRequest =
        new Request(
            UUID.randomUUID(),
            1,
            "가상 성수",
            Set.of(Need.PARKING, Need.HIGH_CHAIR),
            false,
            null,
            Set.of("양식"));
    var result = parser.parse(root.toString(), foodRequest, Mode.MOCK, NOW, NOW.plusSeconds(300));
    assertThat(result.candidates())
        .extracting(Candidate::name)
        .containsExactly("가상 웹추천 식당 1", "가상 웹추천 식당 2", "가상 웹추천 식당 3");
    assertThat(result.excludedCandidates()).isEqualTo(1);
    assertThat(result.candidates())
        .allSatisfy(
            candidate -> assertThat(candidate.remainingQuestions()).contains(Need.HIGH_CHAIR));
  }

  @Test
  void supplementFillsShortlistWithoutRestoringAnExcludedBranch() throws Exception {
    var root = response();
    var body = content(root);
    var candidates = (ArrayNode) body.path("candidates");
    candidates.remove(2);
    ((ObjectNode) candidates.get(2)).put("address", "가상구 가상로 4 2층");
    ((ObjectNode) root.at("/output/1/content/0")).put("text", body.toString());

    var result =
        parser.parse(
            root.toString(),
            request,
            Mode.WEB_SEARCH,
            NOW,
            NOW.plusSeconds(300),
            List.of(supplement("가상 별칭", "가상구 가상로 4"), supplement("가상 추가 식당", "가상구 다른길 9")));
    assertThat(result.candidates())
        .extracting(Candidate::name)
        .containsExactly("가상 웹추천 식당 1", "가상 웹추천 식당 2", "가상 추가 식당");
    assertThat(result.excludedCandidates()).isEqualTo(1);
  }

  @Test
  void verifiedEvidenceRanksEvenFullShortlistsWithoutOverwritingReportedNo() throws Exception {
    var root = response();
    var body = content(root);
    var first = (ObjectNode) body.at("/candidates/0");
    first.put("address", "가상구 가상로 4 2층");
    String directory = "https://directory.example/parking-no";
    ((ObjectNode) root.at("/output/0/action"))
        .putArray("sources")
        .addObject()
        .put("url", directory);
    report(body, 0, Need.PARKING.ordinal()).put("answer", "NO").put("sourceUrl", directory);
    ((ObjectNode) root.at("/output/1/content/0")).put("text", body.toString());
    var needs = Set.of(Need.PARKING, Need.HIGH_CHAIR);
    var query = new Request(UUID.randomUUID(), 1, "가상 지역", needs, false);
    var source = URI.create("https://merchant.example/checked");
    var evidence =
        new Evidence(
            source,
            "가상 매장 안내",
            SourceType.MERCHANT_PAGE,
            NOW,
            null,
            NOW.plusSeconds(300),
            "가상 시설 안내",
            "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
            Model.Scope.GENERAL,
            null);
    var checked =
        new Candidate(
            1,
            first.path("name").asText(),
            "가상구 가상로 4",
            source,
            Fit.POTENTIAL_MATCH,
            needs.stream()
                .map(need -> new Assessment(need, Verdict.YES, List.of(evidence)))
                .toList(),
            List.of(),
            List.of(),
            false,
            false,
            false);
    var result =
        parser.parse(
            root.toString(), query, Mode.WEB_SEARCH, NOW, NOW.plusSeconds(300), List.of(checked));
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.candidates().getFirst().remainingQuestions()).containsExactly(Need.PARKING);
    assertThat(result.candidates().getFirst().assessments())
        .filteredOn(assessment -> assessment.need() == Need.HIGH_CHAIR)
        .extracting(Assessment::verdict)
        .containsExactly(Verdict.YES);
  }

  private Candidate supplement(String name, String address) {
    var assessments =
        request.requiredNeeds().stream()
            .sorted()
            .map(need -> new Assessment(need, Verdict.UNKNOWN, List.of()))
            .toList();
    return new Candidate(
        1,
        name,
        address,
        URI.create("https://merchant.example/supplement"),
        Fit.NEEDS_CONFIRMATION,
        assessments,
        List.of(),
        request.requiredNeeds().stream().sorted().toList(),
        false,
        false,
        false);
  }

  @Test
  void businessDetailsPreferCitedMerchantInfoAndOmitUnsupportedFacts() throws Exception {
    var root = response();
    var body = content(root);
    var candidate = (ObjectNode) body.at("/candidates/0");
    var details = candidate.putArray("businessDetails");
    String merchant = candidate.path("identitySourceUrl").asText();
    String directory = "https://directory.example/fictional-listing";
    ((ArrayNode) root.at("/output/1/content/0/annotations"))
        .addObject()
        .put("type", "url_citation")
        .put("url", directory)
        .put("title", "가상 목록");
    details
        .addObject()
        .put("kind", "OPENING_HOURS")
        .put("condition", "평일 11:00~22:00")
        .put("sourceType", "OTHER")
        .put("sourceUrl", directory)
        .putNull("sourceUpdatedAt");
    details
        .addObject()
        .put("kind", "OPENING_HOURS")
        .put("condition", "평일 12:00~21:00")
        .put("sourceType", "MERCHANT_PAGE")
        .put("sourceUrl", merchant)
        .put("sourceUpdatedAt", NOW.minusSeconds(86_400).toString());
    details
        .addObject()
        .put("kind", "MENU")
        .put("condition", "가상 버섯 정식")
        .put("sourceType", "MERCHANT_PAGE")
        .put("sourceUrl", merchant)
        .putNull("sourceUpdatedAt");
    details
        .addObject()
        .put("kind", "BREAK_TIME")
        .put("condition", "15:00~17:00")
        .put("sourceType", "MERCHANT_PAGE")
        .put("sourceUrl", "https://uncited.example/fictional")
        .putNull("sourceUpdatedAt");

    var result = parse(root, body);
    assertThat(result.state()).isEqualTo(State.RESULTS);
    var output = result.candidates().getFirst().businessDetails();
    assertThat(output).containsOnlyKeys(BusinessDetailKind.OPENING_HOURS, BusinessDetailKind.MENU);
    assertThat(output.get(BusinessDetailKind.OPENING_HOURS).condition())
        .isEqualTo("평일 12:00~21:00");
    assertThat(output.get(BusinessDetailKind.OPENING_HOURS).sourceUpdatedAt())
        .isEqualTo(NOW.minusSeconds(86_400));
    assertThat(result.candidates().get(1).businessDetails()).isEmpty();
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
  void ranksAllEligibleCandidatesBeforeSelectingThreeAndRenumbersThem() throws Exception {
    var root = response();
    var body = content(root);
    var candidates = (ArrayNode) body.path("candidates");
    for (var report : candidates.get(3).path("reports")) {
      ((ObjectNode) report).put("answer", "UNKNOWN");
    }
    var fullMatch = candidates.remove(0);
    candidates.add(fullMatch);

    var result = parse(root, body);
    assertThat(result.excludedCandidates()).isZero();
    assertThat(result.candidates())
        .extracting(Candidate::name)
        .containsExactly("가상 웹추천 식당 1", "가상 웹추천 식당 2", "가상 웹추천 식당 3");
    assertThat(result.candidates()).extracting(Candidate::ordinal).containsExactly(1, 2, 3);
    assertThat(result.candidates().getFirst().reviewSignals()).isEmpty();
  }

  @Test
  void reviewsBreakTiesWithoutVerifyingNeedsOrCountingRepeatedReports() throws Exception {
    var root = response();
    var body = content(root);
    var candidates = (ArrayNode) body.path("candidates");
    for (int index : new int[] {0, 1, 3}) {
      for (var report : candidates.get(index).path("reports")) {
        ((ObjectNode) report)
            .put("answer", index == 0 ? "NO" : "UNKNOWN")
            .put("sourceType", index == 0 ? "VISITOR_REVIEW" : "MERCHANT_PAGE");
      }
    }
    ((ArrayNode) body.at("/candidates/2/reports")).add(report(body, 2, 0).deepCopy());
    var result = parse(root, body);
    assertThat(result.excludedCandidates()).isZero();
    assertThat(result.candidates())
        .extracting(Candidate::name)
        .containsExactly("가상 웹추천 식당 3", "가상 웹추천 식당 2", "가상 웹추천 식당 4");
    var reviewed = result.candidates().getFirst();
    assertThat(reviewed.reviewSignals())
        .hasSize(3)
        .allSatisfy(
            signal -> {
              assertThat(signal.verdict()).isEqualTo(Verdict.YES);
              assertThat(signal.evidence()).hasSize(1);
            });
    assertThat(reviewed.assessments()).allMatch(a -> a.verdict() == Verdict.UNKNOWN);
    assertThat(reviewed.remainingQuestions())
        .containsExactlyInAnyOrderElementsOf(request.requiredNeeds());
    assertThat(reviewed.bookable()).isFalse();
  }

  @Test
  void conflictingVisitorReportsAreVisibleCautionsAndRemainEligible() throws Exception {
    var root = response();
    var body = content(root);
    ((ArrayNode) body.at("/candidates/2/reports"))
        .add(report(body, 2, 0).deepCopy().put("answer", "NO"));
    var reviewed = parse(root, body).candidates().get(2);
    assertThat(reviewed.name()).isEqualTo("가상 웹추천 식당 3");
    assertThat(reviewed.reviewSignals())
        .anySatisfy(
            signal -> {
              assertThat(signal.need()).isEqualTo(Need.PET_FRIENDLY);
              assertThat(signal.verdict()).isEqualTo(Verdict.CONFLICT);
            });
    assertThat(reviewed.remainingQuestions()).contains(Need.PET_FRIENDLY);
    assertThat(reviewed.bookable()).isFalse();
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
              assertThat(candidate.reviewSignals()).isEmpty();
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
