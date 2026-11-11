package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

  static void run(Instrumentation runner, String fixture, int width, float fontScale)
      throws Exception {
    new DestinationDeviceChecks(runner).execute(fixture, width, fontScale);
  }

  private void execute(String fixture, int width, float fontScale) throws Exception {
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
    if (width > 0 && activity.getResources().getConfiguration().screenWidthDp != width) {
      var monitor = runner.addMonitor(ReservationActivity.class.getName(), null, false);
      try (var descriptor =
              runner
                  .getUiAutomation()
                  .executeShellCommand(
                      "am task resize "
                          + activity.getTaskId()
                          + " "
                          + (2535 - width)
                          + " 90 2535 1300");
          var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        input.readAllBytes();
      }
      activity = (ReservationActivity) runner.waitForMonitorWithTimeout(monitor, 5000);
      runner.removeMonitor(monitor);
      check(
          activity != null && activity.getResources().getConfiguration().screenWidthDp == width,
          "resize failed");
    }
    if (fontScale > 0)
      check(
          Math.abs(activity.getResources().getConfiguration().fontScale - fontScale) < 0.01,
          "fontScale mismatch");
    model = (ReservationViewModel) field(activity, "model");
    await(() -> !model.busy() && (model.canCreateIdentity() || model.canWrite()), 15000);
    if (main(model::canCreateIdentity)) {
      await(() -> model.restriction().allowsInput(), 10000);
      click(R.string.auth_create);
    }
    await(model::canWrite, 15000);
    String journal = journal();
    if (fixture.equals("RESTART")) {
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "restart restored candidates");
      click(R.string.destination_open);
      await(() -> field(activity, "destinationPanel") != null, 5000);
      capture("RESTART-empty");
      check(journal.equals(journal()), "restart changed journal");
      Bundle status = new Bundle();
      status.putString("stream", "PASS RESTART: no restored candidates or automatic search\n");
      runner.sendStatus(0, status);
      return;
    }
    click(R.string.destination_open);
    await(() -> field(activity, "destinationPanel") != null, 5000);
    panel = field(activity, "destinationPanel");
    main(
        () -> {
          ((Button) field(panel, "highChair")).performClick();
          return null;
        });
    clickText("한식");
    clickText("양식");
    check(
        main(() -> ((java.util.Set<?>) field(panel, "cuisines")).size() == 2),
        "two cuisines not selected");
    capture(fixture + "-conditions");
    main(
        () -> {
          VehicleUiChecks.layout(activity.getWindow().getDecorView());
          return null;
        });
    long searchStarted = SystemClock.elapsedRealtime();
    click(R.string.destination_search);
    if (fixture.equals("HOLD_LOADING")) {
      await(
          () ->
              java.util.Set.of("LOADING", "QUEUED", "RUNNING")
                  .contains(model.destinationView().state()),
          5000);
      capture("HOLD_LOADING-pending");
      Bundle ready = new Bundle();
      ready.putString(
          "stream", "READY HOLD_LOADING: search pending; externally force-stop isolated package\n");
      runner.sendStatus(0, ready);
      Thread.sleep(45000);
      throw new AssertionError("HOLD_LOADING must be interrupted by host force-stop");
    } else if (fixture.equals("LATE")) {
      await(
          () -> java.util.Set.of("QUEUED", "RUNNING").contains(model.destinationView().state()),
          5000);
      main(
          () -> {
            View more =
                findDescription(
                    activity.getWindow().getDecorView(),
                    activity.getString(R.string.destination_adults_more));
            check(more != null && more.isEnabled(), "adult stepper unavailable");
            more.performClick();
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
    } else if (java.util.Set.of("THREE", "TWO", "EXPIRED", "FIVE_MINUTE", "HOLD", "CLOSE_FAIL")
        .contains(fixture)) {
      await(() -> model.destinationView().state().equals("RESULTS"), 15000);
      check(
          main(() -> model.destinationView().candidates().length())
              == (fixture.equals("TWO") ? 2 : 3),
          "candidate count mismatch");
      main(
          () -> {
            ScrollView scroll = (ScrollView) field(panel, "view");
            View results = (View) field(panel, "results");
            scroll.scrollTo(0, 0);
            return null;
          });
      capture(fixture + "-results");
      main(
          () -> {
            VehicleUiChecks.layout(activity.getWindow().getDecorView());
            return null;
          });
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
      if (fixture.equals("HOLD")) {
        Bundle ready = new Bundle();
        ready.putString(
            "stream", "READY HOLD: results visible; externally force-stop isolated package\n");
        runner.sendStatus(0, ready);
        Thread.sleep(45000);
        throw new AssertionError("HOLD must be interrupted by host force-stop");
      } else if (fixture.equals("CLOSE_FAIL")) {
        click(R.string.preview_cancel);
        await(() -> model.destinationView().cleanupFailed(), 10000);
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "close failure restored candidates");
        capture("CLOSE_FAIL-cleared");
      } else if (fixture.equals("EXPIRED") || fixture.equals("FIVE_MINUTE")) {
        await(
            () -> model.destinationView().state().equals("EXPIRED"),
            fixture.equals("FIVE_MINUTE") ? 310000 : 10000);
        long elapsed = SystemClock.elapsedRealtime() - searchStarted;
        if (fixture.equals("FIVE_MINUTE")) {
          check(
              elapsed >= 295000 && elapsed <= 310000, "not a real five-minute expiry: " + elapsed);
          Bundle timing = new Bundle();
          timing.putString("stream", "REAL_EXPIRY elapsedMs=" + elapsed + "\n");
          runner.sendStatus(0, timing);
          Thread.sleep(3000);
          check(
              main(() -> model.destinationView().state().equals("EXPIRED")),
              "automatic restart after expiry");
        }
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "expired result retained");
        capture(fixture + "-cleared");
      } else {
        click(R.string.destination_change);
        main(
            () -> {
              View more =
                  findDescription(
                      activity.getWindow().getDecorView(),
                      activity.getString(R.string.destination_adults_more));
              check(more != null && more.isEnabled(), "adult stepper unavailable");
              more.performClick();
              return null;
            });
        check(
            main(() -> model.destinationView().candidates().length() == 0),
            "input change retained result");
        click(R.string.destination_food_any);
        check(
            main(() -> ((java.util.Set<?>) field(panel, "cuisines")).isEmpty()),
            "clear cuisine failed");
        clickText("한식");
        clickText("양식");
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
      String expected =
          fixture.equals("EMPTY") ? "EMPTY" : fixture.equals("GET404") ? "NOT_FOUND" : "FAILED";
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
    clickText(activity.getString(label));
  }

  private void clickText(String label) throws Exception {
    main(
        () -> {
          View v = find(activity.getWindow().getDecorView(), label);
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
    if (!v.isShown()) return null;
    if (v instanceof TextView t && label.contentEquals(t.getText())) return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static View findDescription(View v, String value) {
    if (!v.isShown()) return null;
    if (value.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
      return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = findDescription(g.getChildAt(i), value);
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
