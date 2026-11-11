package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Explicit isolated installation only; real UI and HTTP against the #53 MOCK harness. */
final class DestinationDeviceChecks {
  private final Instrumentation runner;
  private ReservationActivity activity;
  private ReservationViewModel model;
  private Object panel;

  private DestinationDeviceChecks(Instrumentation runner) {
    this.runner = runner;
  }

  static void run(Instrumentation runner, String fixture) throws Exception {
    new DestinationDeviceChecks(runner).execute(fixture);
  }

  private void execute(String fixture) throws Exception {
    check(
        runner
            .getTargetContext()
            .getPackageName()
            .equals("ai.pleos.assistant.mock.destinationvalidation"),
        "isolated package required");
    Intent intent =
        new Intent(runner.getTargetContext(), ReservationActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    activity = (ReservationActivity) runner.startActivitySync(intent);
    model = (ReservationViewModel) field(activity, "model");
    await(() -> !model.busy() && (model.canCreateIdentity() || model.canWrite()), 15000);
    if (main(model::canCreateIdentity)) {
      await(() -> model.restriction().allowsInput(), 10000);
      click(R.string.auth_create);
    }
    await(model::canWrite, 15000);
    String journal = journal();
    click(R.string.destination_open);
    panel = field(activity, "destinationPanel");
    check(panel != null, "destination entry missing");
    main(
        () -> {
          ((android.widget.CheckBox) field(panel, "highChair")).setChecked(true);
          return null;
        });
    click(R.string.destination_search);
    if (fixture.equals("LATE")) {
      await(
          () -> java.util.Set.of("QUEUED", "RUNNING").contains(model.destinationView().state()),
          5000);
      main(
          () -> {
            ((EditText) field(panel, "adults")).setText("3");
            return null;
          });
      Thread.sleep(6500);
      check(
          main(
              () ->
                  model.destinationView().state().equals("EDITING")
                      && model.destinationView().candidates().length() == 0),
          "late response restored result");
      capture(fixture + "-invalidated");
    } else if (java.util.Set.of("THREE", "TWO", "EXPIRED").contains(fixture)) {
      await(() -> model.destinationView().state().equals("RESULTS"), 15000);
      check(
          main(() -> model.destinationView().candidates().length())
              == (fixture.equals("TWO") ? 2 : 3),
          "candidate count mismatch");
      main(
          () -> {
            ScrollView scroll = (ScrollView) field(panel, "view");
            View results = (View) field(panel, "results");
            scroll.scrollTo(0, results.getTop());
            return null;
          });
      capture(fixture + "-results");
      click(R.string.destination_details_show);
      capture(fixture + "-evidence");
      click(R.string.destination_details_hide);
      check(
          main(
              () ->
                  contains(
                      activity.getWindow().getDecorView(),
                      activity.getString(R.string.destination_not_bookable))),
          "authority warning missing");
      if (fixture.equals("EXPIRED")) {
        await(() -> model.destinationView().state().equals("EXPIRED"), 10000);
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "expired result retained");
        capture(fixture + "-cleared");
      } else {
        main(
            () -> {
              ((EditText) field(panel, "adults")).setText("3");
              return null;
            });
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "input change retained result");
        click(R.string.destination_search);
        await(() -> model.destinationView().state().equals("RESULTS"), 15000);
        main(
            () -> {
              activity.debugRestriction();
              return null;
            });
        check(
            main(
                () ->
                    !model.canWrite()
                        && model.destinationView().candidates().length() == 0
                        && !((Button) field(panel, "search")).isEnabled()),
            "restriction did not clear and block");
        capture(fixture + "-restricted");
        main(
            () -> {
              activity.debugFailure();
              return null;
            });
        check(
            main(() -> !model.canWrite() && !((Button) field(panel, "search")).isEnabled()),
            "UNKNOWN allowed input");
        main(
            () -> {
              activity.retryDriving();
              return null;
            });
        await(model::canWrite, 10000);
        click(R.string.destination_search);
        await(() -> model.destinationView().state().equals("RESULTS"), 15000);
        click(R.string.preview_cancel);
        check(
            main(
                () ->
                    model.destinationView().state().equals("CANCELLED")
                        && model.destinationView().candidates().length() == 0),
            "cancel retained result");
        click(R.string.destination_search);
        await(() -> model.destinationView().state().equals("RESULTS"), 15000);
        main(
            () -> {
              activity.finish();
              return null;
            });
        runner.waitForIdleSync();
        activity = (ReservationActivity) runner.startActivitySync(intent);
        model = (ReservationViewModel) field(activity, "model");
        await(model::canWrite, 15000);
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "relaunch restored result");
        click(R.string.destination_open);
        panel = field(activity, "destinationPanel");
        capture(fixture + "-relaunch");
      }
    } else {
      String expected = fixture.equals("EMPTY") ? "EMPTY" : "FAILED";
      await(() -> model.destinationView().state().equals(expected), 15000);
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "failure retained result");
      main(
          () -> {
            ((ScrollView) field(panel, "view")).fullScroll(View.FOCUS_DOWN);
            return null;
          });
      capture(fixture + "-state");
    }
    check(journal.equals(journal()), "recommendation changed reservation journal");
    Bundle status = new Bundle();
    status.putString(
        "stream",
        "PASS "
            + fixture
            + ": UI/HTTP, journal unchanged; mock restriction injection distinct from actual driving\n");
    runner.sendStatus(0, status);
  }

  private String journal() {
    return runner
        .getTargetContext()
        .getSharedPreferences("mock-recovery", 0)
        .getString("journal", "");
  }

  private void click(int label) throws Exception {
    main(
        () -> {
          View v = find(activity.getWindow().getDecorView(), activity.getString(label));
          check(v != null && v.isEnabled(), "button missing/disabled: " + label);
          v.performClick();
          return null;
        });
    runner.waitForIdleSync();
  }

  private void capture(String name) throws Exception {
    runner.waitForIdleSync();
    Thread.sleep(500);
    VehicleUiChecks.capture(runner, "destination-" + name);
  }

  private <T> T main(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    runner.runOnMainSync(task);
    return task.get();
  }

  private void await(Callable<Boolean> condition, long millis) throws Exception {
    long end = SystemClock.elapsedRealtime() + millis;
    while (SystemClock.elapsedRealtime() < end) {
      if (main(condition)) return;
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out, state="
            + main(() -> model.destinationView().state())
            + " failure="
            + main(model::failureCode));
  }

  private static Object field(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(o);
  }

  private static View find(View v, String label) {
    if (v instanceof TextView t && label.contentEquals(t.getText())) return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static boolean contains(View v, String text) {
    return find(v, text) != null;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
