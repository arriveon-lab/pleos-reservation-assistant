package ai.pleos.assistant.ux;

import java.time.Instant;
import org.json.JSONObject;

/** A paired test session is not necessarily LIVE: absent/expired/MOCK bindings fail closed. */
public final class LiveTrialPolicy {
  private LiveTrialPolicy() {}

  public static boolean ready(JSONObject binding, Instant now) {
    try {
      return binding.getBoolean("active")
          && "LIVE".equals(binding.getString("mode"))
          && binding.getString("phoneLast4").matches("[0-9]{4}")
          && Instant.parse(binding.getString("expiresAt")).isAfter(now);
    } catch (Exception invalid) {
      return false;
    }
  }

  public static boolean confirmed(JSONObject result) {
    return result != null
        && "LIVE".equals(result.optString("mode"))
        && "CONFIRMED".equals(result.optString("status"))
        && result.optString("confirmedTime").matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
  }
}
