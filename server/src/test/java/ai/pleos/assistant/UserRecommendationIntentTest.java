package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UserRecommendationIntentTest {
  @Test
  void infersRestaurantOnlyForClearRequests() {
    assertThat(UserRecommendationIntent.venueCategory("주차 가능한 식당 추천"))
        .isEqualTo(WebRecommendation.VenueCategory.RESTAURANT);
    assertThat(UserRecommendationIntent.venueCategory("주차가능한밥집추천"))
        .isEqualTo(WebRecommendation.VenueCategory.RESTAURANT);
    for (String phrase : new String[] {"근처 추천", "식당과 카페 추천", "식당은 빼고 추천", "식당 아니어도 돼"}) {
      assertThat(UserRecommendationIntent.venueCategory(phrase)).isNull();
    }
  }

  @Test
  void extractsCuisineWithoutOverridingCompanionRequirements() {
    assertThat(UserRecommendationIntent.preferredCuisines("양식 먹고 싶어", Set.of(), true))
        .containsExactly("양식");
    assertThat(UserRecommendationIntent.preferredCuisines("양식 말고 한식 먹자", Set.of(), true))
        .containsExactly("한식");
    assertThat(UserRecommendationIntent.preferredCuisines("한식이나 양식", Set.of(), true))
        .containsExactlyInAnyOrder("한식", "양식");
    assertThat(UserRecommendationIntent.preferredCuisines("성수에서 서양 음식 먹고 싶어", Set.of(), true))
        .containsExactly("양식");
    assertThat(UserRecommendationIntent.preferredCuisines("내일 식사할 곳 추천해줘", Set.of(), false))
        .isEmpty();
    assertThat(UserRecommendationIntent.preferredCuisines("", Set.of(), true)).isEmpty();
  }

  @Test
  void unclearOrOnlyExcludedPreferencesRequireClarification() {
    for (String phrase : new String[] {"양식 말고", "양식 아니어도 돼", "맛있는 거", "양식 싫어", "양식\n먹고 싶어"}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> UserRecommendationIntent.preferredCuisines(phrase, Set.of(), true));
    }
  }

  @Test
  void user_phrases_add_only_supported_conditions_with_mandatory_parking() {
    assertThat(UserRecommendationIntent.requiredFeatures("지금 도착지 근처에 갈만한 맛집 추천해줘", Set.of()))
        .containsExactly("parking");
    assertThat(UserRecommendationIntent.requiredFeatures("도착지 근처 맛집 추천해줘. 아기 의자가 있는 곳으로", Set.of()))
        .containsExactlyInAnyOrder("parking", "highChair");
    assertThat(UserRecommendationIntent.requiredFeatures("어르신이 있으니 계단 같은거 많이 없는 곳으로", Set.of()))
        .containsExactlyInAnyOrder("parking", "stepFreeAccess");
  }
}
