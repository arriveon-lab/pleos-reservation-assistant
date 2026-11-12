package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GumiRestaurantCatalogTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void publicCampusAddressReturnsThreeRestaurantsWithSourcedDetails() {
    var candidates =
        GumiRestaurantCatalog.search(
                request("경상북도 구미시 3공단3로 302 (임수동)", VenueCategory.RESTAURANT), CLOCK)
            .orElseThrow()
            .candidates();
    assertThat(candidates)
        .extracting(Candidate::name)
        .containsExactly("천생산아구찜", "원조옛날국밥구미점", "칼잡이남자");
    assertThat(candidates).extracting(Candidate::ordinal).containsExactly(1, 2, 3);
    assertThat(candidates)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.identitySourceUrl().getHost()).isEqualTo("www.gumi.go.kr");
              assertThat(candidate.naverPlaceUrl().getHost()).isEqualTo("map.naver.com");
              assertThat(candidate.businessDetails())
                  .containsKeys(BusinessDetailKind.OPENING_HOURS, BusinessDetailKind.BREAK_TIME);
              assertThat(candidate.assessments())
                  .anySatisfy(
                      assessment -> {
                        assertThat(assessment.need()).isEqualTo(Need.PARKING);
                        assertThat(assessment.evidence()).hasSize(1);
                      });
              assertThat(candidate.bookable()).isFalse();
            });
    assertThat(candidates.get(2).remainingQuestions()).contains(Need.PARKING, Need.HIGH_CHAIR);
    assertThat(candidates.getFirst().remainingQuestions()).containsExactly(Need.HIGH_CHAIR);
    assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.reviewSignals()).isEmpty());
  }

  @Test
  void equalUnresolvedNeedsPreserveDistanceOrder() {
    var request =
        new Request(
            UUID.randomUUID(),
            1,
            "경북 구미시 3공단3로 302",
            Set.of(Need.HIGH_CHAIR),
            false,
            VenueCategory.RESTAURANT);
    assertThat(GumiRestaurantCatalog.search(request, CLOCK).orElseThrow().candidates())
        .extracting(Candidate::name)
        .containsExactly("칼잡이남자", "천생산아구찜", "원조옛날국밥구미점");
  }

  @Test
  void foodPreferenceBypassesTheUnclassifiedStaticCatalog() {
    var request =
        new Request(
            UUID.randomUUID(),
            1,
            "경북 구미시 3공단3로 302",
            Set.of(Need.PARKING),
            false,
            VenueCategory.RESTAURANT,
            Set.of("양식"));
    assertThat(GumiRestaurantCatalog.search(request, CLOCK)).isEmpty();
  }

  @Test
  void anotherAddressOrCafeSearchUsesTheExistingProvider() {
    assertThat(
            GumiRestaurantCatalog.search(
                request("경북 구미시 3공단3로 302-1", VenueCategory.RESTAURANT), CLOCK))
        .isEmpty();
    assertThat(GumiRestaurantCatalog.search(request("경북 구미시 3공단3로 302", VenueCategory.CAFE), CLOCK))
        .isEmpty();
    assertThat(GumiRestaurantCatalog.search(request("경북 구미시 3공단3로 302", null), CLOCK)).isPresent();
  }

  private static Request request(String area, VenueCategory category) {
    return new Request(
        UUID.randomUUID(), 1, area, Set.of(Need.PARKING, Need.HIGH_CHAIR), false, category);
  }
}
