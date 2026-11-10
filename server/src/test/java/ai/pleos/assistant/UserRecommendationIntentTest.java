package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UserRecommendationIntentTest {
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
