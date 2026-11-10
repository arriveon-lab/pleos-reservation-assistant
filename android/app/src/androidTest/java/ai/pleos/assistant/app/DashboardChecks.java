package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

/** UI-only fixtures. Never replaces durable recovery state or sends business commands. */
final class DashboardChecks {
  static void run(Instrumentation runner, ReservationActivity activity, ReservationViewModel model)
      throws Exception {
    Field snapshot = field(model, "snapshot"),
        busy = field(model, "busy"),
        failed = field(model, "failed");
    Object original = snapshot.get(model);
    try {
      main(
          runner,
          () -> {
            snapshot.set(model, new JSONObject());
            render(activity);
          });
      runner.waitForIdleSync();
      Thread.sleep(300);
      main(
          runner,
          () -> {
            View root = activity.getWindow().getDecorView();
            for (int label :
                new int[] {
                  R.string.home_destination,
                  R.string.home_eta_unavailable,
                  R.string.home_phone,
                  R.string.home_booking,
                  R.string.home_pet,
                  R.string.home_pet_unavailable
                })
              if (!contains(root, activity.getString(label)))
                throw new AssertionError("dashboard label missing: " + activity.getString(label));
            VehicleUiChecks.layout(root);
            EditText adults = (EditText) field(activity, "adults").get(activity);
            int before = Integer.parseInt(adults.getText().toString());
            Button plus =
                find(
                    root,
                    activity.getString(
                        R.string.home_increase, activity.getString(R.string.adults)));
            plus.performClick();
            if (Integer.parseInt(adults.getText().toString()) != before + 1)
              throw new AssertionError("stepper not connected");
            adults.setText(Integer.toString(before));
          });
      capture(runner, "dashboard-ready");
      main(
          runner,
          () -> {
            busy.setBoolean(model, true);
            failed.setBoolean(model, true);
            render(activity);
            View root = activity.getWindow().getDecorView();
            if (contains(root, activity.getString(R.string.auth_recovery_notice)))
              throw new AssertionError("stale error shown while loading");
            if (find(
                    root,
                    activity.getString(R.string.home_increase, activity.getString(R.string.adults)))
                .isEnabled()) throw new AssertionError("busy stepper enabled");
          });
      capture(runner, "dashboard-loading");
    } finally {
      main(
          runner,
          () -> {
            snapshot.set(model, original);
            busy.setBoolean(model, false);
            failed.setBoolean(model, false);
            render(activity);
          });
      runner.runOnMainSync(activity::finish);
    }
  }

  private static Field field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private static void render(ReservationActivity a) throws Exception {
    var m = ReservationActivity.class.getDeclaredMethod("render");
    m.setAccessible(true);
    m.invoke(a);
  }

  private interface Work {
    void run() throws Exception;
  }

  private static void main(Instrumentation r, Work work) throws Exception {
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              work.run();
              return null;
            });
    r.runOnMainSync(task);
    task.get();
  }

  private static void capture(Instrumentation r, String name) throws Exception {
    r.waitForIdleSync();
    Thread.sleep(500);
    VehicleUiChecks.capture(r, name);
  }

  private static boolean contains(View v, String text) {
    if (v instanceof TextView t && text.contentEquals(t.getText())) return true;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) if (contains(g.getChildAt(i), text)) return true;
    return false;
  }

  private static Button find(View v, String description) {
    if (v instanceof Button b && description.contentEquals(b.getContentDescription())) return b;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        Button b = find(g.getChildAt(i), description);
        if (b != null) return b;
      }
    return null;
  }
}
