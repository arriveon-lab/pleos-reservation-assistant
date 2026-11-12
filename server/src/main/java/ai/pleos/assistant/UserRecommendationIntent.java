package ai.pleos.assistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded Korean request mapping. The raw utterance is never sent to a provider or stored. */
public final class UserRecommendationIntent {
  static final Set<String> CUISINES = Set.of("한식", "양식", "일식", "중식", "분식", "아시아음식");
  private static final Pattern CUISINE_WORD =
      Pattern.compile(
          "(?<![가-힣A-Za-z])(?:아시아\\s*음식|한국\\s*음식|서양\\s*음식|일본\\s*음식|중국\\s*음식|한식|양식|일식|중식|분식)");
  private static final Map<String, String> ALIASES =
      Map.of("한국음식", "한식", "서양음식", "양식", "일본음식", "일식", "중국음식", "중식");
  private static final Pattern NEGATED = Pattern.compile("말고|빼고|제외|싫|안먹|먹기싫|먹고싶지않|먹지않|안좋아|원하지않|아닌");

  private UserRecommendationIntent() {}

  /** Extract only supported food categories locally; never forward the original utterance. */
  public static Set<String> preferredCuisines(
      String utterance, Set<String> explicit, boolean dedicatedField) {
    if (explicit == null || !CUISINES.containsAll(explicit))
      throw new IllegalArgumentException("Unsupported cuisine");
    if (utterance == null || utterance.isBlank()) return Set.copyOf(explicit);
    if (!WebRecommendation.text(utterance, dedicatedField ? 160 : 300))
      throw new IllegalArgumentException("Invalid preference text");
    String compact = utterance.replaceAll("\\s+", "");
    if (Set.of("아무거나", "상관없어", "상관없음", "음식종류상관없어").contains(compact)) return Set.copyOf(explicit);
    var mentions = new ArrayList<java.util.regex.MatchResult>();
    var matcher = CUISINE_WORD.matcher(utterance);
    while (matcher.find()) mentions.add(matcher.toMatchResult());
    var preferred = new HashSet<>(explicit);
    var excluded = new HashSet<String>();
    for (int index = 0; index < mentions.size(); index++) {
      var mention = mentions.get(index);
      String word = mention.group().replaceAll("\\s+", "");
      String cuisine = ALIASES.getOrDefault(word, word);
      int end = index + 1 < mentions.size() ? mentions.get(index + 1).start() : utterance.length();
      String after = utterance.substring(mention.end(), end).replaceAll("\\s+", "");
      if (after.contains("아니어도") || after.contains("상관없") || after.contains("모르겠"))
        throw new IllegalArgumentException("Ambiguous cuisine preference");
      if (NEGATED.matcher(after).find()) excluded.add(cuisine);
      else preferred.add(cuisine);
    }
    if (preferred.stream().anyMatch(excluded::contains)
        || (!mentions.isEmpty() && preferred.isEmpty())
        || (dedicatedField && mentions.isEmpty() && preferred.isEmpty()))
      throw new IllegalArgumentException("Unclear cuisine preference");
    return Set.copyOf(preferred);
  }

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
