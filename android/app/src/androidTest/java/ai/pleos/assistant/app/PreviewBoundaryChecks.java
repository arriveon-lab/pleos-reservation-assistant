package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real device/HTTP checks; requires the existing authenticated MOCK RESULTS installation. */
final class PreviewBoundaryChecks {
  private final Instrumentation runner;
  private ReservationActivity activity;
  private ReservationViewModel model;
  private Object panel;

  private PreviewBoundaryChecks(Instrumentation runner) {
    this.runner = runner;
  }

  static void run(Instrumentation runner) throws Exception {
    new PreviewBoundaryChecks(runner).execute();
  }

  private void execute() throws Exception {
    activity =
        (ReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), ReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    model = (ReservationViewModel) field(activity, "model");
    await(() -> model.canWrite(), 15000);
    main(
        () -> {
          activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          model.openPreview();
          return null;
        });
    try {
      panel = field(activity, "previewPanel");
      valid(2, 1, "01-two-preferences");
      invalid(21, 10, "02-adults21");
      invalid(20, 11, "03-children11");
      invalid(0, 0, "04-adults0");
      valid(1, 0, "05-lower-boundary");
      valid(20, 10, "06-upper-boundary");
      JSONObject result = main(() -> model.previewView().result());
      Instant fetched = Instant.parse(result.getString("fetchedAt"));
      Instant expires = Instant.parse(result.getString("expiresAt"));
      check(
          Duration.between(fetched, expires).equals(Duration.ofMinutes(5)), "server TTL not 300s");
      long start = SystemClock.elapsedRealtime();
      long waitMillis = Duration.between(Instant.now(), expires).toMillis();
      check(waitMillis > 280000 && waitMillis <= 300000, "unexpected remaining TTL");
      report("Real TTL: fetchedAt=" + fetched + " expiresAt=" + expires);
      while (SystemClock.elapsedRealtime() - start < waitMillis - 15000) {
        Thread.sleep(Math.min(30000, waitMillis - 15000 - (SystemClock.elapsedRealtime() - start)));
        report("Waiting real expiry: elapsedMs=" + (SystemClock.elapsedRealtime() - start));
      }
      check(
          main(
              () ->
                  contains(
                      activity.getWindow().getDecorView(),
                      activity.getString(R.string.preview_results))),
          "result disappeared before expiry");
      capture("07-before-real-expiry");
      long remaining = waitMillis + 2000 - (SystemClock.elapsedRealtime() - start);
      if (remaining > 0) Thread.sleep(remaining);
      // Inspect rendered UI first: previewView() itself expires data and must not drive this
      // assertion.
      await(
          () ->
              contains(
                  activity.getWindow().getDecorView(),
                  activity.getString(R.string.preview_expired)),
          5000);
      check(
          main(
              () ->
                  !contains(
                      activity.getWindow().getDecorView(),
                      activity.getString(R.string.preview_results))),
          "old result heading remained");
      check(main(() -> model.previewView().result() == null), "expired payload retained");
      capture("08-real-expired");
      report("PASS real expiry; elapsedMs=" + (SystemClock.elapsedRealtime() - start));
      Thread.sleep(5000);
      check(
          main(
              () ->
                  contains(
                      activity.getWindow().getDecorView(),
                      activity.getString(R.string.preview_expired))),
          "expiry auto-researched");
    } finally {
      main(
          () -> {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return null;
          });
    }
  }

  private void fill(int adults, int children) throws Exception {
    main(
        () -> {
          ((EditText) field(panel, "adults")).setText(Integer.toString(adults));
          ((EditText) field(panel, "children")).setText(Integer.toString(children));
          ((EditText) field(panel, "cuisines")).setText("한식,양식");
          ((CheckBox) field(panel, "parking")).setChecked(true);
          ((CheckBox) field(panel, "childFriendly")).setChecked(true);
          return null;
        });
    await(() -> !model.previewView().cleanupPending(), 10000);
  }

  private void valid(int adults, int children, String name) throws Exception {
    fill(adults, children);
    main(
        () -> {
          ((Button) field(panel, "search")).performClick();
          return null;
        });
    await(() -> "RESULTS".equals(model.previewView().state()), 15000);
    JSONObject result = main(() -> model.previewView().result());
    JSONObject plan = result.getJSONObject("recommendationPlan");
    JSONObject req = plan.getJSONObject("requirements");
    check(req.getJSONObject("party").getInt("adults") == adults, "adult echo mismatch");
    check(req.getJSONObject("party").getInt("children") == children, "child echo mismatch");
    JSONArray cuisines = req.getJSONArray("preferredCuisines");
    check(
        cuisines.length() == 2
            && Set.of(cuisines.getString(0), cuisines.getString(1)).equals(Set.of("한식", "양식")),
        "preference set mismatch");
    JSONArray candidates = plan.getJSONArray("candidates");
    check(candidates.length() == 3, "candidate count mismatch");
    for (int i = 0; i < candidates.length(); i++) {
      JSONObject candidate = candidates.getJSONObject(i);
      check(
          !candidate.getBoolean("bookable") && !candidate.getBoolean("inquiryDispatchAllowed"),
          "preview granted authority");
    }
    capture(name);
    report("PASS " + name + " party=" + adults + "/" + children + " preferences=2 candidates=3");
  }

  private void invalid(int adults, int children, String name) throws Exception {
    fill(adults, children);
    main(
        () -> {
          ((Button) field(panel, "search")).performClick();
          return null;
        });
    await(() -> "INVALID_INPUT".equals(model.previewView().state()), 5000);
    check(main(() -> model.previewView().result() == null), "invalid input retained result");
    capture(name);
    report(
        "PASS " + name + " INVALID_INPUT; HTTP absence must be checked in passive transport log");
  }

  private void capture(String name) throws Exception {
    runner.waitForIdleSync();
    Thread.sleep(600);
    VehicleUiChecks.capture(runner, name);
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
    throw new AssertionError("device condition timed out");
  }

  private void report(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    runner.sendStatus(0, status);
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static boolean contains(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), text)) return true;
    return false;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
