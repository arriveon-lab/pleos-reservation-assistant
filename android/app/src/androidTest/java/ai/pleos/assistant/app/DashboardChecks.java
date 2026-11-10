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
      main(
          runner,
          () -> {
            busy.setBoolean(model, false);
            failed.setBoolean(model, false);
          });
      String[] statuses = {"PENDING", "CONFIRMED", "REJECTED", "UNKNOWN"};
      int[] hints = {
        R.string.home_action_wait,
        R.string.home_action_confirmed,
        R.string.home_action_rejected,
        R.string.home_action_unknown
      };
      for (int i = 0; i < statuses.length; i++) {
        String status = statuses[i];
        int hint = hints[i];
        main(
            runner,
            () -> {
              JSONObject state = new JSONObject(original.toString());
              state.remove("pending");
              JSONObject reservation =
                  new JSONObject()
                      .put("status", status)
                      .put("originalRequestedTime", "2026-11-10T12:00:00Z")
                      .put("confirmedTime", "2026-11-10T12:00:00Z")
                      .put("totalAdditionalCostKrw", 0);
              state
                  .put("reservation", reservation)
                  .put("reservationId", "ui-fixture")
                  .put(
                      "call",
                      new JSONObject()
                          .put("purpose", "BOOKING")
                          .put("status", "CONFIRMED".equals(status) ? "ACCEPTED" : status));
              snapshot.set(model, state);
              render(activity);
              View root = activity.getWindow().getDecorView();
              if (!contains(root, activity.getString(hint))
                  || contains(root, activity.getString(R.string.home_ready_hint))
                  || contains(root, activity.getString(R.string.reconcile_search_closed)))
                throw new AssertionError("Wrong booking guidance: " + status);
              if (!contains(
                  root,
                  activity.getString(
                      R.string.home_phone_status,
                      activity.getString(R.string.call_booking),
                      activity.getString(
                          "CONFIRMED".equals(status)
                              ? R.string.call_accepted
                              : "PENDING".equals(status)
                                  ? R.string.call_pending
                                  : "REJECTED".equals(status)
                                      ? R.string.call_rejected
                                      : R.string.call_unknown))))
                throw new AssertionError("Booking mislabeled as inquiry");
            });
        capture(runner, "guidance-" + status.toLowerCase(java.util.Locale.ROOT));
      }
      main(
          runner,
          () -> {
            JSONObject state = new JSONObject(original.toString());
            state.remove("pending");
            state.put(
                "reservation",
                new JSONObject()
                    .put("status", "CONFIRMED")
                    .put("pendingChangeCallId", "ui-change"));
            state.put("call", new JSONObject().put("purpose", "CHANGE").put("status", "UNKNOWN"));
            snapshot.set(model, state);
            render(activity);
            View root = activity.getWindow().getDecorView();
            if (!contains(root, activity.getString(R.string.home_action_unknown))
                || contains(root, activity.getString(R.string.home_action_confirmed)))
              throw new AssertionError("Unknown change hidden by confirmed reservation");
          });
      main(
          runner,
          () -> {
            JSONObject state = new JSONObject(original.toString());
            state.remove("pending");
            state.put("reservationId", "cooldown-fixture");
            state.put(
                "reservation",
                new JSONObject()
                    .put("status", "CONFIRMED")
                    .put("originalRequestedTime", "2026-11-10T12:00:00Z")
                    .put("confirmedTime", "2026-11-10T12:15:00Z")
                    .put("totalAdditionalCostKrw", 0)
                    .put("lastChangeRequestedAt", java.time.Instant.now().toString()));
            snapshot.set(model, state);
            render(activity);
            if (contains(activity.getWindow().getDecorView(), activity.getString(R.string.change)))
              throw new AssertionError("Premature change action visible");
          });
      runner.waitForIdleSync();
      Thread.sleep(300);
      main(runner, () -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
      capture(runner, "change-cooldown");
      main(
          runner,
          () -> {
            snapshot.set(model, new JSONObject());
            render(activity);
            if (!model.canWrite()) throw new AssertionError("Unrestricted fixture not writable");
            activity.debugRestriction();
            if (model.canWrite()) throw new AssertionError("Restriction allowed input");
            activity.debugFailure();
            if (model.canWrite()) throw new AssertionError("Unknown failure allowed input");
          });
      capture(runner, "core-restriction-failure");
      runner.runOnMainSync(activity::retryDriving);
      runner.waitForIdleSync();
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
