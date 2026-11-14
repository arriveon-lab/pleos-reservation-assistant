package ai.pleos.assistant.ux;

import org.json.JSONObject;

/** Untrusted external input. No defaults, coercion, location, or booking authority. */
public record GleoSearchCommand(int adults, int children) {
  public static final String ACTION = "SEARCH_MOCK_RESTAURANTS";

  public static GleoSearchCommand parse(JSONObject values) throws Exception {
    if (values.length() != 2 || !values.has("adults") || !values.has("children"))
      throw new IllegalArgumentException("INVALID_VOICE_INPUT");
    return new GleoSearchCommand(
        number(values.get("adults"), 1, 20), number(values.get("children"), 0, 10));
  }

  private static int number(Object value, int min, int max) {
    if (!(value instanceof Number n)) throw new IllegalArgumentException("INVALID_VOICE_INPUT");
    double d = n.doubleValue();
    if (!Double.isFinite(d) || d != Math.rint(d) || d < min || d > max)
      throw new IllegalArgumentException("INVALID_VOICE_INPUT");
    return (int) d;
  }
}
