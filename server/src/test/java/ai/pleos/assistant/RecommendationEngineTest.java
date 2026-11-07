package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecommendationEngineTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private static final Instant ETA = NOW.plusSeconds(3600);
  private final RecommendationEngine engine =
      new RecommendationEngine(Clock.fixed(NOW, ZoneOffset.UTC));

  private Search search(List<Candidate> candidates) {
    Search search = new Search();
    search.request =
        new SearchRequest(
            new Navigation(
                "fictional-trip",
                new Destination("가상 목적지", 37.544, 127.055),
                ETA,
                NOW,
                NavigationSource.MOCK),
            new Party(2, 0),
            15,
            Set.of("parking"),
            Set.of("한식"),
            false);
    search.candidates = new ArrayList<>(candidates);
    return search;
  }

  private Fact fact(Object value, Scope scope, Instant appliesAt) {
    return new Fact(
        value,
        Mode.MOCK,
        "MOCK_TEST",
        "fictional-evidence",
        NOW,
        appliesAt,
        NOW.plusSeconds(300),
        Certainty.CONFIRMED,
        scope);
  }

  private Candidate ready(String id) {
    Candidate candidate = new Candidate();
    candidate.id = id;
    candidate.name = "가상 식당";
    candidate.cuisine = "한식";
    candidate.facts.put("parking", fact(true, Scope.GENERAL, NOW));
    candidate.facts.put("arrivalWaitMinutes", fact(10, Scope.ARRIVAL, ETA));
    candidate.facts.put("reservationAvailable", fact(true, Scope.ARRIVAL, ETA));
    return candidate;
  }

  static Stream<Fact> invalidArrivalFacts() {
    return Stream.of(
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.NOW),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.GENERAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA.plusSeconds(1),
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            null,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW.minusSeconds(300),
            ETA,
            NOW,
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW.plusSeconds(1),
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.UNKNOWN,
            Scope.ARRIVAL),
        new Fact(
            10, Mode.MOCK, "MOCK_TEST", "ref", NOW, ETA, null, Certainty.CONFIRMED, Scope.ARRIVAL),
        new Fact(
            10,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            null,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10,
            Mode.LIVE,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            "10",
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            10.5,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            -1,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL),
        new Fact(
            Double.NaN,
            Mode.MOCK,
            "MOCK_TEST",
            "ref",
            NOW,
            ETA,
            NOW.plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.ARRIVAL));
  }

  @ParameterizedTest
  @MethodSource("invalidArrivalFacts")
  void invalidWaitEvidenceCannotMakeACandidateBookable(Fact invalid) {
    Candidate candidate = ready("fictional-1");
    candidate.facts.put("arrivalWaitMinutes", invalid);
    engine.rank(search(List.of(candidate)));
    assertThat(candidate.missing).containsExactly("arrivalWaitMinutes");
    assertThat(candidate.bookable).isFalse();
    assertThat(candidate.exclusionReasons).isEmpty();
  }

  @Test
  void reservationAvailabilityRequiresABooleanForTheMatchingArrivalScope() {
    Candidate candidate = ready("fictional-1");
    candidate.facts.put("reservationAvailable", fact(true, Scope.NOW, ETA));
    engine.rank(search(List.of(candidate)));
    assertThat(candidate.missing).containsExactly("reservationAvailable");
    candidate.facts.put("reservationAvailable", fact("true", Scope.ARRIVAL, ETA));
    engine.rank(search(List.of(candidate)));
    assertThat(candidate.bookable).isFalse();
    assertThat(candidate.missing).containsExactly("reservationAvailable");
  }

  @Test
  void invalidRequiredEvidenceAndUnknownCuisineDoNotCrashOrCountAsConfirmed() {
    Candidate candidate = ready("fictional-1");
    candidate.cuisine = null;
    candidate.facts.put("parking", fact("true", Scope.GENERAL, NOW));
    engine.rank(search(List.of(candidate)));
    assertThat(candidate.missing).containsExactly("parking");
    assertThat(candidate.bookable).isFalse();
    assertThat(candidate.preferenceScore).isZero();
  }

  @Test
  void selectionEvaluatesTheWholePoolBeforeReturningThreeAndPreservesExclusionReasons() {
    Candidate excluded = ready("a");
    excluded.facts.put("parking", fact(false, Scope.GENERAL, NOW));
    Candidate unknown = ready("b");
    unknown.facts.remove("parking");
    Candidate preferred = ready("d");
    Candidate otherCuisine = ready("c");
    otherCuisine.cuisine = "양식";
    Search search = search(List.of(excluded, unknown, otherCuisine, preferred));
    engine.select(search);
    assertThat(search.candidates).extracting(c -> c.id).containsExactly("d", "c", "b");
    assertThat(excluded.exclusionReasons).containsExactly("parking");
    assertThat(unknown.bookable).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 5})
  void shortResultsAreNeverPaddedAndSelectionsRemainMutableForLaterReranking(int count) {
    Search search =
        search(IntStream.range(0, count).mapToObj(i -> ready("fictional-" + i)).toList());
    engine.select(search);
    assertThat(search.candidates).hasSize(Math.min(3, count));
    engine.rank(search);
    assertThat(search.candidates).allMatch(c -> c.bookable);
  }

  @Test
  void inquiryUpdatesRerankWithoutGivingLateEvidenceANewLifetime() {
    Candidate candidate = ready("fictional-1");
    candidate.facts.clear();
    Search search = search(List.of(candidate));
    engine.rank(search);
    assertThat(candidate.bookable).isFalse();
    engine.apply(
        candidate,
        new InquiryFacts(null, true, true, true, 5, 10, ETA),
        "fictional-call-1",
        ETA,
        NOW);
    engine.rank(search);
    assertThat(candidate.bookable).isTrue();
    engine.apply(
        candidate,
        new InquiryFacts(null, false, null, null, null, null, null),
        "fictional-call-old",
        ETA,
        NOW.minusSeconds(600));
    assertThat(candidate.facts.get("parking").observedAt()).isEqualTo(NOW);
    assertThat(candidate.facts.get("parking").value()).isEqualTo(true);
    assertThat(candidate.facts.get("parking").validUntil()).isEqualTo(NOW.plusSeconds(300));
  }
}
