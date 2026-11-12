package ai.pleos.assistant.ux;

import java.time.Instant;
import org.json.JSONObject;

/** Display-only projection. Eligibility is never evidence that requirements are confirmed. */
public final class CandidateSummary {
  private CandidateSummary() {}

  public enum State {
    EXCLUDED,
    REVIEW_REQUIRED,
    REQUEST_AVAILABLE
  }

  public static State state(JSONObject candidate) {
    if (!candidate.optBoolean("eligible")) return State.EXCLUDED;
    return candidate.optBoolean("bookable") ? State.REQUEST_AVAILABLE : State.REVIEW_REQUIRED;
  }

  public static String fact(JSONObject facts, String key, Instant now, Instant eta) {
    JSONObject fact = facts.optJSONObject(key);
    if (fact == null
        || fact.isNull("value")
        || "UNKNOWN".equals(fact.optString("certainty", "UNKNOWN"))) return "UNKNOWN";
    try {
      if (!Instant.parse(fact.getString("validUntil")).isAfter(now)) return "EXPIRED";
      if (("arrivalWaitMinutes".equals(key) || "reservationAvailable".equals(key))
          && (!"ARRIVAL".equals(fact.optString("scope"))
              || !eta.equals(Instant.parse(fact.getString("appliesAt"))))) return "UNKNOWN";
      if (!java.util.Set.of("CONFIRMED", "ESTIMATED").contains(fact.optString("certainty")))
        return "UNKNOWN";
      Object value = fact.get("value");
      String prefix = "ESTIMATED".equals(fact.optString("certainty")) ? "ESTIMATED:" : "";
      if ("arrivalWaitMinutes".equals(key))
        return value instanceof Number n
                && Double.isFinite(n.doubleValue())
                && n.doubleValue() >= 0
                && n.doubleValue() <= 240
                && n.doubleValue() == n.intValue()
            ? prefix + value.toString()
            : "UNKNOWN";
      return value instanceof Boolean ? prefix + value.toString() : "UNKNOWN";
    } catch (Exception invalid) {
      return "UNKNOWN";
    }
  }
}
