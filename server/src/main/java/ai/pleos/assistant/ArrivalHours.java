package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.BusinessDetailKind.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/** Evaluates only explicit daily schedules. Unstructured hours remain unknown. */
final class ArrivalHours {
  private static final String TIME = "(?:[01][0-9]|2[0-3]):[0-5][0-9]";
  private static final Pattern RANGE =
      Pattern.compile("(" + TIME + ")\\s*[~–-]\\s*(" + TIME + "|24:00)");
  private static final Pattern LAST = Pattern.compile("매일 (" + TIME + ")");

  private ArrivalHours() {}

  static boolean closedAt(
      LocalDateTime arrival,
      Map<WebRecommendation.BusinessDetailKind, WebRecommendation.Evidence> facts) {
    if (arrival == null) return false;
    int time = arrival.getHour() * 60 + arrival.getMinute();
    String hours = merchantFact(facts.get(OPENING_HOURS));
    String breaks = merchantFact(facts.get(BREAK_TIME));
    if (insideDailyRanges(hours, time) == Boolean.FALSE
        || insideDailyRanges(breaks, time) == Boolean.TRUE) return true;
    var last = LAST.matcher(merchantFact(facts.get(LAST_ORDER)));
    // A single non-overnight window makes the ordering cutoff unambiguous.
    var window = RANGE.matcher(hours.startsWith("매일 ") ? hours.substring(3) : "");
    return last.matches()
        && window.matches()
        && minutes(window.group(1)) < minutes(window.group(2))
        && time >= minutes(last.group(1));
  }

  private static String merchantFact(WebRecommendation.Evidence evidence) {
    return evidence != null && evidence.sourceType() == WebRecommendation.SourceType.MERCHANT_PAGE
        ? evidence.condition()
        : "";
  }

  private static Boolean insideDailyRanges(String condition, int time) {
    if (!condition.startsWith("매일 ")) return null;
    boolean inside = false;
    for (String part : condition.substring(3).split(",", -1)) {
      var range = RANGE.matcher(part.strip().replaceFirst("^(점심|저녁)\\s+", ""));
      if (!range.matches()) return null;
      int start = minutes(range.group(1)), end = minutes(range.group(2));
      if (start == end) return null;
      inside |= start < end ? start <= time && time < end : time >= start || time < end;
    }
    return inside;
  }

  private static int minutes(String hhmm) {
    return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
  }
}
