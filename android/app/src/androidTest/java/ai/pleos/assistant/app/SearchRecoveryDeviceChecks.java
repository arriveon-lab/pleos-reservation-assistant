package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

/**
 * Uses the existing authenticated installation and server, never fabricates or deletes its pending.
 */
final class SearchRecoveryDeviceChecks {
  static void run(Instrumentation runner, boolean restart) throws Exception {
    ReservationActivity activity =
        (ReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), ReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Field field = ReservationActivity.class.getDeclaredField("model");
    field.setAccessible(true);
    ReservationViewModel model = (ReservationViewModel) field.get(activity);
    await(runner, () -> !model.busy() && model.connected() && model.restriction().allowsInput());
    if (!restart) {
      JSONObject before = main(runner, () -> new JSONObject(model.snapshot().toString()));
      JSONObject pending = before.getJSONObject("pending");
      if (!"/searches".equals(pending.getString("path")))
        throw new AssertionError("not original search pending");
      main(
          runner,
          () -> {
            click(
                activity.getWindow().getDecorView(), activity.getString(R.string.reconcile_search));
            return null;
          });
      await(runner, () -> !model.busy() && !model.snapshot().has("pending"));
      JSONObject after = main(runner, () -> model.snapshot());
      var history = after.getJSONArray("searchReconciliations");
      JSONObject record = history.getJSONObject(history.length() - 1);
      if (!pending.toString().equals(record.getJSONObject("command").toString()))
        throw new AssertionError("original command changed");
      if (!before.getString("authBinding").equals(after.getString("authBinding"))
          || !before.getString("tripId").equals(after.getString("tripId")))
        throw new AssertionError("identity changed");
      if (!"REJECTED_CLOSED".equals(record.getJSONObject("result").getString("state")))
        throw new AssertionError("expected old request closure");
      if (!main(runner, model::canWrite))
        throw new AssertionError("input not released after saved receipt");
      Thread.sleep(500);
      VehicleUiChecks.capture(runner, "search-reconciled");
    } else {
      JSONObject state = main(runner, () -> model.snapshot());
      if (state.has("pending") || state.getJSONArray("searchReconciliations").length() == 0)
        throw new AssertionError("receipt not restored");
      if (!main(runner, model::canWrite)) throw new AssertionError("restored input blocked");
      main(
          runner,
          () -> {
            click(activity.getWindow().getDecorView(), activity.getString(R.string.search));
            return null;
          });
      await(
          runner,
          () ->
              !model.busy()
                  && model.snapshot().has("searchId")
                  && !model.snapshot().has("pending"));
      if (main(runner, () -> model.snapshot().getJSONArray("searchReconciliations").length())
          != state.getJSONArray("searchReconciliations").length())
        throw new AssertionError("closure history changed");
      Thread.sleep(500);
      VehicleUiChecks.capture(runner, "search-after-restart");
    }
    runner.runOnMainSync(activity::finish);
  }

  private static void click(View view, String label) {
    Button found = find(view, label);
    if (found == null || !found.isEnabled())
      throw new AssertionError("button unavailable: " + label);
    found.performClick();
  }

  private static Button find(View view, String label) {
    if (view instanceof Button button && label.contentEquals(button.getText())) return button;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static <T> T main(Instrumentation runner, Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    runner.runOnMainSync(task);
    return task.get();
  }

  private static void await(Instrumentation runner, Callable<Boolean> condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < end) {
      if (main(runner, condition)) return;
      Thread.sleep(100);
    }
    throw new AssertionError("recovery device timeout");
  }
}
