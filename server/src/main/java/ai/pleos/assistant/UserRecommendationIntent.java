package ai.pleos.assistant;

import java.util.HashSet;
import java.util.Set;

/** Bounded Korean request mapping. The raw utterance is never sent to a provider or stored. */
public final class UserRecommendationIntent {
  private UserRecommendationIntent() {}

  public static Set<String> requiredFeatures(String utterance, Set<String> explicit) {
    if (utterance == null
        || utterance.isBlank()
        || utterance.length() > 300
        || utterance.codePoints().anyMatch(Character::isISOControl)
        || explicit == null) throw new IllegalArgumentException("Invalid recommendation request");
    String compact = utterance.replaceAll("\\s+", "");
    var features = new HashSet<>(explicit);
    features.add("parking");
    if (compact.contains("아기의자") || compact.contains("유아의자") || compact.contains("하이체어"))
      features.add("highChair");
    if (compact.contains("계단")
        || compact.contains("무장애")
        || compact.contains("휠체어")
        || compact.contains("어르신")
        || compact.contains("노약자")) features.add("stepFreeAccess");
    return Set.copyOf(features);
  }
}
