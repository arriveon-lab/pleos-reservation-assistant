package ai.pleos.assistant;

import static ai.pleos.assistant.PlaceRecommendationPlanner.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlaceRecommendationPlannerTest {
  private static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");
  private final PlaceRecommendationPlanner planner =
      new PlaceRecommendationPlanner(Clock.fixed(NOW, ZoneOffset.UTC));
  private final PlaceQuery query = new PlaceQuery(37.544, 127.055, 1000, 15);
  private final Requirements needs =
      new Requirements(new Model.Party(2, 1), 15, Set.of("parking"), Set.of("한식"));

  private PlaceSummary place(String id, String cuisine, double offset) {
    return new PlaceSummary(
        PlaceSummary.Provider.MOCK,
        id,
        "가상 후보 " + id,
        cuisine,
        query.latitude() + offset,
        query.longitude(),
        "가상 주소",
        "fictional-contact-not-dialable",
        "https://example.invalid/listing",
        999999);
  }

  private Plan evaluate(List<PlaceSummary> places) {
    return planner.evaluate(
        PlaceSummary.Provider.MOCK,
        query,
        new PlaceSearchResult.Success(places, NOW.minusSeconds(5), 0),
        needs);
  }

  @Test
  void ranksAllInputsBeforeTakingThreeAndDoesNotInventMore() {
    var places =
        new ArrayList<>(
            IntStream.range(0, 14).mapToObj(i -> place("other-" + i, "양식", i * .0001)).toList());
    places.add(place("preferred", "한식", .005));
    var result = evaluate(places);
    assertThat(result.scannedRecords()).isEqualTo(15);
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.candidates().getFirst().name()).isEqualTo("가상 후보 preferred");
    assertThat(result.candidates().getFirst().reasons()).contains(Reason.PREFERRED_CUISINE_MATCH);
  }

  @Test
  void orderIsDeterministicAndDistanceIsComputedNotTrustedFromProviderField() {
    var places =
        new ArrayList<>(
            List.of(place("b", "한식", .001), place("a", "한식", .001), place("near", "한식", 0)));
    var first = evaluate(places);
    Collections.reverse(places);
    assertThat(evaluate(places)).isEqualTo(first);
    assertThat(first.candidates())
        .extracting(Candidate::name)
        .containsExactly("가상 후보 near", "가상 후보 a", "가상 후보 b");
    assertThat(first.candidates().getFirst().straightLineDistanceMeters()).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 5})
  void preservesZeroOrSmallResultCounts(int count) {
    var result =
        evaluate(IntStream.range(0, count).mapToObj(i -> place("p" + i, "한식", 0)).toList());
    assertThat(result.candidates()).hasSize(Math.min(3, count));
    assertThat(result.state()).isEqualTo(count == 0 ? State.EMPTY : State.RESULTS);
  }

  @Test
  void requiredUnknownsAreNeverAffirmedByCuisineOrContactAvailability() {
    var candidate = evaluate(List.of(place("a", "한식", 0))).candidates().getFirst();
    assertThat(candidate.qualification()).isEqualTo(Qualification.NEEDS_CONFIRMATION);
    assertThat(candidate.bookable()).isFalse();
    assertThat(candidate.inquiryDispatchAllowed()).isFalse();
    assertThat(candidate.questions())
        .extracting(Question::field)
        .containsExactly(
            Field.CHILD_FRIENDLY,
            Field.PARKING,
            Field.ARRIVAL_WAIT_MINUTES,
            Field.RESERVATION_AVAILABLE);
    assertThat(candidate.questions()).allMatch(q -> q.certainty() == Model.Certainty.UNKNOWN);
    assertThat(candidate.questions().stream().filter(Question::arrivalTimeRequired))
        .allMatch(q -> q.scope() == Model.Scope.ARRIVAL);
    assertThat(candidate.questions().get(2).prompt()).contains("성인 2명", "아동 1명", "15분");
  }

  @Test
  void childrenAutomaticallyRequireChildFriendlyButNoOptionalQuestionIsAdded() {
    var onlyArrival = new Requirements(new Model.Party(1, 0), 0, Set.of(), Set.of());
    var result =
        planner.evaluate(
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(List.of(place("a", "양식", 0)), NOW, 0),
            onlyArrival);
    assertThat(result.candidates().getFirst().questions())
        .extracting(Question::field)
        .containsExactly(Field.ARRIVAL_WAIT_MINUTES, Field.RESERVATION_AVAILABLE);
    assertThat(result.candidates().getFirst().questions().getFirst().prompt()).contains("0분");
  }

  @Test
  void nullBasicFieldsAreReportedWithoutInventingFactsOrFailingPreferenceMatch() {
    var p =
        new PlaceSummary(
            PlaceSummary.Provider.MOCK,
            "fictional",
            "가상 누락",
            null,
            query.latitude(),
            query.longitude(),
            null,
            null,
            null,
            null);
    var candidate = evaluate(List.of(p)).candidates().getFirst();
    assertThat(candidate.missingInformation())
        .containsExactly(
            MissingInformation.CUISINE, MissingInformation.ADDRESS, MissingInformation.PHONE);
    assertThat(candidate.reasons()).doesNotContain(Reason.PREFERRED_CUISINE_MATCH);
  }

  @Test
  void deduplicatesEqualRecordsAndRejectsConflictingDuplicateIdentity() {
    var p = place("a", "한식", 0);
    var result = evaluate(List.of(p, p));
    assertThat(result.duplicateRecords()).isEqualTo(1);
    assertThat(result.candidates()).hasSize(1);
    var conflicting = evaluate(List.of(p, place("a", "양식", 0)));
    assertThat(conflicting.state()).isEqualTo(State.FAILED);
    assertThat(conflicting.failure()).isEqualTo(PlaceSearchFailure.INVALID_RESPONSE);
  }

  @Test
  void filtersGeometryBeforePreferenceAndDistinguishesFilteredFromUpstreamEmpty() {
    var result = evaluate(List.of(place("far-preferred", "한식", 1), place("near", "양식", 0)));
    assertThat(result.outsideWindowRecords()).isEqualTo(1);
    assertThat(result.candidates()).extracting(Candidate::name).containsExactly("가상 후보 near");
    assertThat(evaluate(List.of(place("far", "한식", 1))).state()).isEqualTo(State.FILTERED);
  }

  @Test
  void radiusToleranceHasAnExplicitThirtyMetreBoundary() {
    double metresPerDegree = 6371000 * Math.PI / 180;
    var result =
        evaluate(
            List.of(
                place("inside", "한식", 1029 / metresPerDegree),
                place("outside", "한식", 1031 / metresPerDegree)));
    assertThat(result.outsideWindowRecords()).isEqualTo(1);
    assertThat(result.candidates()).extracting(Candidate::name).containsExactly("가상 후보 inside");
  }

  @ParameterizedTest
  @EnumSource(PlaceSearchFailure.class)
  void preservesProviderFailureWithoutTurningItIntoEmptyOrRetrying(PlaceSearchFailure kind) {
    var result =
        planner.evaluate(
            PlaceSummary.Provider.MOCK, query, new PlaceSearchResult.Failure(kind), needs);
    assertThat(result.state()).isEqualTo(State.FAILED);
    assertThat(result.failure()).isEqualTo(kind);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.listingFetchedAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(longs = {-301, -300, 1})
  void rejectsExpiredExactBoundaryOrFutureListingTimestamp(long seconds) {
    var result =
        planner.evaluate(
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(
                List.of(place("a", "한식", 0)), NOW.plusSeconds(seconds), 0),
            needs);
    assertThat(result.failure()).isEqualTo(PlaceSearchFailure.INVALID_RESPONSE);
  }

  @Test
  void keepsOriginalReceiptTimeAndDoesNotClaimMerchantFactsWereRefreshed() {
    var result = evaluate(List.of(place("a", "한식", 0)));
    assertThat(result.listingFetchedAt()).isEqualTo(NOW.minusSeconds(5));
    assertThat(result.displayValidUntil()).isEqualTo(NOW.plusSeconds(295));
  }

  @Test
  void partialRejectionIsPreservedWithoutDiscardingUsableListing() {
    var result =
        planner.evaluate(
            PlaceSummary.Provider.MOCK,
            query,
            new PlaceSearchResult.Success(List.of(place("a", "한식", 0)), NOW, 2),
            needs);
    assertThat(result.state()).isEqualTo(State.RESULTS);
    assertThat(result.rejectedRecords()).isEqualTo(2);
    assertThat(result.candidates()).hasSize(1);
  }

  @Test
  void exampleCannotSwitchToLiveOrAcceptCredentialArguments() {
    assertThatThrownBy(() -> RecommendationPlanDemo.main(new String[] {"live"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongSourceOversizedNullAndAllRejectedResponses() {
    assertThat(
            planner
                .evaluate(
                    PlaceSummary.Provider.KAKAO,
                    query,
                    new PlaceSearchResult.Success(List.of(place("a", "한식", 0)), NOW, 0),
                    needs)
                .failure())
        .isEqualTo(PlaceSearchFailure.INVALID_RESPONSE);
    assertThat(
            planner
                .evaluate(
                    PlaceSummary.Provider.MOCK,
                    new PlaceQuery(37.544, 127.055, 1000, 1),
                    new PlaceSearchResult.Success(
                        List.of(place("a", "한식", 0), place("b", "한식", 0)), NOW, 0),
                    needs)
                .state())
        .isEqualTo(State.FAILED);
    assertThat(planner.evaluate(PlaceSummary.Provider.MOCK, query, null, needs).state())
        .isEqualTo(State.FAILED);
    assertThat(
            planner
                .evaluate(
                    PlaceSummary.Provider.MOCK,
                    query,
                    new PlaceSearchResult.Success(List.of(), NOW, 1),
                    needs)
                .state())
        .isEqualTo(State.FAILED);
  }

  @Test
  void syntheticKakaoListingCannotAcquireMockBusinessFacts() {
    var source = place("synthetic-source-only", "한식", 0);
    var synthetic =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            source.providerPlaceId(),
            source.name(),
            source.cuisine(),
            source.latitude(),
            source.longitude(),
            null,
            null,
            null,
            null);
    var result =
        planner.evaluate(
            PlaceSummary.Provider.KAKAO,
            query,
            new PlaceSearchResult.Success(List.of(synthetic), NOW, 0),
            needs);
    assertThat(result.provider()).isEqualTo(PlaceSummary.Provider.KAKAO);
    assertThat(result.candidates().getFirst().bookable()).isFalse();
    assertThat(result.candidates().getFirst().questions())
        .allMatch(q -> q.certainty() == Model.Certainty.UNKNOWN);
  }

  @Test
  void copiesInputCollectionsAndDoesNotExposeMutableResultLists() {
    var features = new HashSet<>(Set.of("parking"));
    var req = new Requirements(new Model.Party(2, 0), 10, features, Set.of());
    features.clear();
    assertThat(req.requiredFeatures()).containsExactly("parking");
    var result = evaluate(List.of(place("a", "한식", 0)));
    assertThatThrownBy(() -> result.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.candidates().getFirst().questions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  static Stream<Object[]> invalidRequirements() {
    return Stream.of(
        new Object[] {new Model.Party(0, 0), 10, Set.of(), Set.of()},
        new Object[] {new Model.Party(21, 0), 10, Set.of(), Set.of()},
        new Object[] {new Model.Party(2, -1), 10, Set.of(), Set.of()},
        new Object[] {new Model.Party(2, 11), 10, Set.of(), Set.of()},
        new Object[] {new Model.Party(2, 0), -1, Set.of(), Set.of()},
        new Object[] {new Model.Party(2, 0), 121, Set.of(), Set.of()},
        new Object[] {new Model.Party(2, 0), 10, Set.of("inventedFact"), Set.of()},
        new Object[] {new Model.Party(2, 0), 10, Set.of(), Set.of(" ")},
        new Object[] {new Model.Party(2, 0), 10, Set.of(), Set.of("x".repeat(31))});
  }

  @ParameterizedTest
  @MethodSource("invalidRequirements")
  void validatesRequirementBoundaries(
      Model.Party party, int wait, Set<String> features, Set<String> cuisines) {
    assertThatThrownBy(() -> new Requirements(party, wait, features, cuisines))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mockAdapterToSerializedPlanHasNoRawContactIdCoordinatesOrApproval() throws Exception {
    var adapter = new MockPlaceSearchAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
    var result = planner.evaluate(PlaceSummary.Provider.MOCK, query, adapter.search(query), needs);
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    String serialized = json.writeValueAsString(result);
    var contactFixture =
        new PlaceSummary(
            PlaceSummary.Provider.MOCK,
            "private-fake-id",
            "가상 연락처 검증 식당",
            "한식",
            query.latitude(),
            query.longitude(),
            "가상 주소",
            "fictional-contact-not-dialable",
            "https://example.invalid/listing",
            null);
    String contactPlan = json.writeValueAsString(evaluate(List.of(contactFixture)));
    assertThat(contactPlan)
        .doesNotContain(
            "fictional-contact-not-dialable",
            "private-fake-id",
            "providerPlaceId",
            "placeUrl",
            "latitude",
            "longitude");
    assertThat(serialized)
        .contains("childFriendly", "arrivalWaitMinutes", "UNKNOWN", "NEEDS_CONFIRMATION")
        .doesNotContain(
            "mock-1", "providerPlaceId", "latitude", "longitude", "fictional-contact-not-dialable");
    assertThat(result.candidates())
        .hasSize(3)
        .allMatch(c -> !c.bookable() && !c.inquiryDispatchAllowed());
    Path evidence = Path.of("build/rec08/mock-recommendation-plan.json");
    Files.createDirectories(evidence.getParent());
    Files.writeString(
        evidence, json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
  }
}
