package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.GleoSearchCommand;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

/** Official Gleo example uses ACTION_PARAM; prose also documents individual extras. */
final class GleoIntentAdapter {
  static GleoSearchCommand parse(Intent intent) throws Exception {
    Bundle extras = intent.getExtras();
    if (extras == null) throw new IllegalArgumentException();
    JSONObject values;
    if (extras.containsKey("ACTION_PARAM")) {
      if (extras.containsKey("adults") || extras.containsKey("children"))
        throw new IllegalArgumentException();
      Object raw = extras.get("ACTION_PARAM");
      if (!(raw instanceof String text) || text.length() > 1024)
        throw new IllegalArgumentException();
      values = new JSONObject(text);
    } else {
      values = new JSONObject();
      values.put("adults", extras.get("adults"));
      values.put("children", extras.get("children"));
    }
    return GleoSearchCommand.parse(values);
  }

  static void reply(Context context, boolean accepted) {
    try {
      JSONObject result =
          new JSONObject()
              .put("functionName", GleoSearchCommand.ACTION)
              .put("status", accepted ? "success" : "fail")
              .put(
                  "message",
                  accepted
                      ? "MOCK recommendation search started using the displayed fictional destination. No booking or call was made. Check the app for results."
                      : "MOCK search was not started. Check the app connection, driving restriction and adult and child counts, then explicitly retry.");
      context.sendBroadcast(
          new Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
              .setPackage("ai.umos.ambientai")
              .putExtra("packageName", context.getPackageName())
              .putExtra("response", result.toString()));
    } catch (Exception failure) {
      android.util.Log.w("ArriveOnGleo", "CALLBACK_FAILED");
    }
  }
}
