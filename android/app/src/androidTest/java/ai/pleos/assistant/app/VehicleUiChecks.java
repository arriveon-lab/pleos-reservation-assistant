package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Real measured widget bounds, not assertions against layout constants alone. */
final class VehicleUiChecks {
  private VehicleUiChecks() {}

  static void layout(View view) {
    if (!view.isShown()) return;
    if (view instanceof EditText && view.getHeight() < VehicleUi.dp(view.getContext(), 72))
      throw new AssertionError("Input target too short");
    if (view instanceof TextView text && !(view instanceof EditText) && text.getLayout() != null) {
      android.text.Layout layout = text.getLayout();
      int available =
          text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
      for (int line = 0; line < layout.getLineCount(); line++) {
        if (layout.getEllipsisCount(line) > 0 || layout.getLineWidth(line) > available + 2)
          throw new AssertionError("Text clipped: " + text.getText());
      }
      if (view instanceof Button && view.getHeight() < VehicleUi.dp(view.getContext(), 72))
        throw new AssertionError("Button target too short: " + text.getText());
      if (text.getText().toString().contains("조건 충족:"))
        throw new AssertionError("Eligibility presented as confirmation");
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) layout(group.getChildAt(i));
  }

  static void resultStates(ReservationActivity activity, ReservationViewModel model) {
    try {
      java.lang.reflect.Field snapshot = ReservationViewModel.class.getDeclaredField("snapshot");
      snapshot.setAccessible(true);
      java.lang.reflect.Method render = ReservationActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      Object original = snapshot.get(model);
      try {
        String[] states = {"PENDING", "CONFIRMED", "REJECTED", "UNKNOWN"};
        int[] labels = {
          R.string.status_pending,
          R.string.status_confirmed,
          R.string.status_rejected,
          R.string.status_unknown
        };
        for (int i = 0; i < states.length; i++) {
          org.json.JSONObject reservation =
              new org.json.JSONObject()
                  .put("status", states[i])
                  .put("originalRequestedTime", "2026-11-09T09:30:00Z")
                  .put("confirmedTime", "2026-11-09T09:30:00Z")
                  .put("totalAdditionalCostKrw", 0)
                  .put("pendingChangeCallId", "fixture-change-unknown");
          snapshot.set(
              model,
              new org.json.JSONObject()
                  .put("reservationId", "fixture-only")
                  .put("reservation", reservation));
          render.invoke(activity);
          View root = activity.getWindow().getDecorView();
          if (!contains(root, activity.getString(labels[i]))
              || !contains(root, activity.getString(R.string.change_unresolved)))
            throw new AssertionError(
                "Result/unchanged original time guidance missing: " + states[i]);
        }
      } finally {
        snapshot.set(model, original);
        render.invoke(activity);
      }
    } catch (ReflectiveOperationException | org.json.JSONException error) {
      throw new AssertionError(error);
    }
  }

  private static boolean contains(View view, String expected) {
    if (view instanceof TextView text && expected.contentEquals(text.getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), expected)) return true;
    return false;
  }

  static void prepareDraft(ReservationActivity activity) {
    try {
      java.lang.reflect.Field show = ReservationActivity.class.getDeclaredField("showConditions");
      show.setAccessible(true);
      show.setBoolean(activity, true);
      java.lang.reflect.Method render = ReservationActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      render.invoke(activity);
      java.lang.reflect.Field adults = ReservationActivity.class.getDeclaredField("adults");
      adults.setAccessible(true);
      ((EditText) adults.get(activity)).setText("3");
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  static void checkDraft(ReservationActivity activity) {
    try {
      java.lang.reflect.Field adults = ReservationActivity.class.getDeclaredField("adults");
      adults.setAccessible(true);
      if (!"3".contentEquals(((EditText) adults.get(activity)).getText()))
        throw new AssertionError("Draft input lost on recreation");
      layout(activity.getWindow().getDecorView());
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  static void capture(Instrumentation runner, String name) throws Exception {
    Bitmap screenshot = runner.getUiAutomation().takeScreenshot();
    if (screenshot == null) throw new AssertionError("Screenshot unavailable");
    File path = new File(runner.getTargetContext().getFilesDir(), name + ".png");
    try (FileOutputStream output = new FileOutputStream(path)) {
      screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      screenshot.recycle();
    }
  }
}
