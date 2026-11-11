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
            String previousInput = adults.getText().toString();
            // The retained input may already be at the upper bound after prior device checks.
            adults.setText("2");
            Button plus =
                find(
                    root,
                    activity.getString(
                        R.string.home_increase, activity.getString(R.string.adults)));
            plus.performClick();
            if (Integer.parseInt(adults.getText().toString()) != 3)
              throw new AssertionError("stepper not connected");
            adults.setText(previousInput);
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
                      .put("id", "ui-fixture")
                      .put("searchId", "ui-search")
                      .put("bookingCallId", "ui-booking")
                      .put("status", status)
                      .put("originalRequestedTime", "2026-11-10T12:00:00Z")
                      .put(
                          "confirmedTime",
                          "CONFIRMED".equals(status) ? "2026-11-10T12:00:00Z" : JSONObject.NULL)
                      .put("totalAdditionalCostKrw", 0);
              state
                  .put("reservation", reservation)
                  .put("reservationId", "ui-fixture")
                  .put("searchId", "ui-search")
                  .put(
                      "call",
                      new JSONObject()
                          .put("id", "ui-booking")
                          .put("searchId", "ui-search")
                          .put("reservationId", "ui-fixture")
                          .put("requestedTime", "2026-11-10T12:00:00Z")
                          .put("purpose", "BOOKING")
                          .put("status", "CONFIRMED".equals(status) ? "ACCEPTED" : status));
              snapshot.set(model, state);
              render(activity);
              View root = activity.getWindow().getDecorView();
              if (!contains(root, activity.getString(hint))
                  || contains(root, activity.getString(R.string.home_ready_hint))
                  || contains(root, activity.getString(R.string.reconcile_search_closed)))
                throw new AssertionError("Wrong booking guidance: " + status);
              boolean rebooking = contains(root, activity.getString(R.string.rebooking_start));
              if (rebooking != "REJECTED".equals(status))
                throw new AssertionError("Rebooking offered for wrong status: " + status);
              if (rebooking) {
                String beforeDialog = state.toString();
                find(root, activity.getString(R.string.rebooking_start)).performClick();
                android.app.AlertDialog dialog =
                    (android.app.AlertDialog) field(activity, "approval").get(activity);
                if (!dialog.isShowing()
                    || !contains(
                        dialog.getWindow().getDecorView(),
                        activity.getString(R.string.rebooking_body)))
                  throw new AssertionError("Rebooking needs explicit preparation notice");
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
                if (!beforeDialog.equals(snapshot.get(model).toString()))
                  throw new AssertionError("Dismissed rebooking changed the snapshot");
              }
              if ("UNKNOWN".equals(status)
                  && (!contains(root, activity.getString(R.string.unknown_read_only))
                      || !contains(root, activity.getString(R.string.unknown_refresh))))
                throw new AssertionError("Unknown booking read-only guidance missing");
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
        runner.waitForIdleSync();
        Thread.sleep(300);
        main(runner, () -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
        capture(runner, "guidance-" + status.toLowerCase(java.util.Locale.ROOT));
        if ("REJECTED".equals(status)) {
          main(
              runner,
              () -> {
                Button action =
                    find(
                        activity.getWindow().getDecorView(),
                        activity.getString(R.string.rebooking_start));
                action.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, action.getWidth(), action.getHeight()), true);
              });
          runner.waitForIdleSync();
          Thread.sleep(300);
          main(
              runner,
              () -> {
                Button action =
                    find(
                        activity.getWindow().getDecorView(),
                        activity.getString(R.string.rebooking_start));
                android.graphics.Rect visible = new android.graphics.Rect();
                if (!action.getGlobalVisibleRect(visible) || visible.height() < action.getHeight())
                  throw new AssertionError("Rebooking action not reachable by scrolling");
              });
          capture(runner, "rebooking-action");
        }
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
                    .put("originalRequestedTime", "2026-11-10T12:00:00Z")
                    .put("confirmedTime", "2026-11-10T12:00:00Z")
                    .put("totalAdditionalCostKrw", 0)
                    .put("pendingChangeCallId", "ui-change"));
            state.put(
                "call",
                new JSONObject()
                    .put("requestedTime", "2026-11-10T12:15:00Z")
                    .put("purpose", "CHANGE")
                    .put("status", "UNKNOWN"));
            snapshot.set(model, state);
            render(activity);
            View root = activity.getWindow().getDecorView();
            if (!contains(root, activity.getString(R.string.home_action_unknown))
                || contains(root, activity.getString(R.string.home_action_confirmed)))
              throw new AssertionError("Unknown change hidden by confirmed reservation");
            if (!contains(root, activity.getString(R.string.unknown_read_only))
                || !contains(root, activity.getString(R.string.unknown_refresh)))
              throw new AssertionError("Unknown change read-only guidance missing");
          });
      runner.waitForIdleSync();
      Thread.sleep(300);
      main(runner, () -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
      capture(runner, "change-unknown");
      for (String outcome : new String[] {"ACCEPTED", "REJECTED"}) {
        main(
            runner,
            () -> {
              JSONObject state = new JSONObject(snapshot.get(model).toString());
              state
                  .getJSONObject("reservation")
                  .put("pendingChangeCallId", JSONObject.NULL)
                  .put(
                      "confirmedTime",
                      "ACCEPTED".equals(outcome) ? "2026-11-10T12:15:00Z" : "2026-11-10T12:00:00Z");
              state.getJSONObject("call").put("status", outcome);
              snapshot.set(model, state);
              render(activity);
              View root = activity.getWindow().getDecorView();
              int hint =
                  "ACCEPTED".equals(outcome)
                      ? R.string.home_action_change_accepted
                      : R.string.home_action_change_rejected;
              if (!contains(root, activity.getString(hint))
                  || contains(root, activity.getString(R.string.home_action_confirmed)))
                throw new AssertionError("Completed change hidden by booking: " + outcome);
            });
        runner.waitForIdleSync();
        Thread.sleep(300);
        main(runner, () -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
        capture(runner, "change-" + outcome.toLowerCase(java.util.Locale.ROOT));
      }

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
    if (v instanceof Button b
        && ((b.getContentDescription() != null
                && description.contentEquals(b.getContentDescription()))
            || description.contentEquals(b.getText()))) return b;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        Button b = find(g.getChildAt(i), description);
        if (b != null) return b;
      }
    return null;
  }
}
