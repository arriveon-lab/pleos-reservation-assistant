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
    if (fixture.startsWith("PHONE_")) new PhoneDeviceChecks(runner).run(fixture);
    else new DestinationDeviceChecks(runner).execute(fixture, width, fontScale);
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
    if (fixture.equals("NAVI_DEMO")) {
      check(BuildConfig.NAVIGATION_DEMO, "demo flag required");
      click(R.string.destination_open);
      panel = field(activity, "destinationPanel");
      await(() -> model.navigationState().equals("UNVERIFIED"), 15000);
      var received = main(model::navigationDestination);
      check(!main(model::navigationReady), "search allowed before confirmation");
      // Use only visible buttons: no Gleo invocation and no voice Intent.
      click(R.string.navigation_demo_confirm);
      check(main(model::navigationReady), "demo confirmation did not allow search");
      check(
          !main(() -> model.navigationDestination().currentRouteVerified()),
          "demo claimed SDK proof");
      check(
          main(
              () ->
                  model.navigationDestination().name().equals(received.name())
                      && model.navigationDestination().latitude() == received.latitude()
                      && model.navigationDestination().longitude() == received.longitude()),
          "demo changed destination");
      main(
          () -> {
            findDescription(
                    activity.getWindow().getDecorView(),
                    activity.getString(R.string.destination_children_more))
                .performClick();
            return null;
          });
      click(R.string.destination_search);
      await(() -> model.destinationView().state().equals("RESULTS"), 15000);
      check(
          main(() -> model.destinationView().candidates().length() == 3),
          "MOCK candidates missing");
      // Actual SDK name/coordinates must not enter screenshot/log fixtures.
      click(R.string.destination_exit);
      check(!main(model::navigationReady), "exit retained demo consent");
      click(R.string.destination_open);
      click(R.string.navigation_demo_confirm);
      click(R.string.navigation_retry);
      await(() -> model.navigationState().equals("UNVERIFIED"), 15000);
      check(!main(model::navigationReady), "refresh retained demo consent");
      click(R.string.navigation_demo_confirm);
      var sdkListener =
          (ai.pleos.playground.navi.helper.listener.NaviHelperEventListener)
              field(field(activity, "navigation"), "listener");
      sdkListener.onRouteCancelled();
      runner.waitForIdleSync();
      check(!main(model::navigationReady), "cancel retained demo consent");
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "cancel retained results");
      capture("NAVI_DEMO-cancelled");
      check(journal.equals(journal()), "demo changed reservation journal");
      return;
    }
    if (fixture.equals("NAVI_RECOVERY")) {
      click(R.string.destination_open);
      panel = field(activity, "destinationPanel");
      await(() -> model.navigationState().equals("UNVERIFIED"), 15000);
      check(!main(model::navigationReady), "SDK fields became verified");
      Object firstAdapter = field(activity, "navigation");
      var oldListener =
          (ai.pleos.playground.navi.helper.listener.NaviHelperEventListener)
              field(firstAdapter, "listener");
      click(R.string.navigation_retry);
      check(firstAdapter != field(activity, "navigation"), "retry reused old listener owner");
      oldListener.onRouteCancelled();
      oldListener.onDestinationInfo(null);
      await(() -> model.navigationState().equals("UNVERIFIED"), 15000);
      check(!main(model::navigationReady), "retry verified route association");
      Object secondAdapter = field(activity, "navigation");
      // Deterministic timeout delivery; this is not a real service outage or elapsed-time test.
      main(
          () -> {
            ((Runnable) field(secondAdapter, "responseTimeout")).run();
            return null;
          });
      check(main(() -> model.navigationState().equals("TIMEOUT")), "timeout reason overwritten");
      check(main(() -> model.navigationDestination() == null), "timeout retained destination");
      check(main(() -> !((Button) field(panel, "search")).isEnabled()), "timeout enabled search");
      capture("NAVI_RECOVERY-timeout");
      var timedOutListener =
          (ai.pleos.playground.navi.helper.listener.NaviHelperEventListener)
              field(secondAdapter, "listener");
      timedOutListener.onRoutingCompleted();
      runner.waitForIdleSync();
      check(main(() -> model.navigationState().equals("TIMEOUT")), "late event revived timeout");
      click(R.string.navigation_retry);
      await(() -> model.navigationState().equals("UNVERIFIED"), 15000);
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "retry searched automatically");
      check(journal.equals(journal()), "navigation retry changed booking journal");
      return;
    }
    // Synthetic navigation is installed only by instrumentation. Production SDK stays UNVERIFIED.
    main(
        () -> {
          ((NavigationDestinationAdapter) field(activity, "navigation")).close();
          model.navigation(
              new ai.pleos.assistant.ux.DestinationController.Destination(
                  "가상 내비 검증 장소", 37.544, 127.055, true),
              "FIXTURE");
          return null;
        });
    if (fixture.equals("NAVIGATION")) {
      click(R.string.destination_open);
      panel = field(activity, "destinationPanel");
      main(
          () -> {
            model.navigation(null, "UNAVAILABLE");
            check(
                !((Button) field(panel, "search")).isEnabled(),
                "missing navigation enabled search");
            model.navigation(
                new ai.pleos.assistant.ux.DestinationController.Destination(
                    "가상 내비 검증 장소", 37.544, 127.055, false),
                "UNVERIFIED");
            check(
                !((Button) field(panel, "search")).isEnabled(),
                "unverified navigation enabled search");
            return null;
          });
      capture("NAVIGATION-unverified");
      main(
          () -> {
            model.navigation(
                new ai.pleos.assistant.ux.DestinationController.Destination(
                    "가상 내비 검증 장소", 37.544, 127.055, true),
                "FIXTURE");
            return null;
          });
      click(R.string.destination_search);
      await(() -> model.destinationView().state().equals("RESULTS"), 15000);
      capture("NAVIGATION-results");
      main(
          () -> {
            model.navigation(null, "UNAVAILABLE");
            return null;
          });
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "route change retained results");
      check(journal.equals(journal()), "navigation changed booking journal");
      return;
    }

    if (fixture.equals("GLEO")) {
      String beforeOpen = main(() -> model.destinationView().state());
      main(
          () -> {
            activity.onNewIntent(new Intent(activity.getPackageName() + ".OPEN_ARRIVEON"));
            return null;
          });
      check(beforeOpen.equals(main(() -> model.destinationView().state())), "open started search");
      check(journal.equals(journal()), "open changed journal");
      main(
          () -> {
            activity.onNewIntent(
                new Intent(activity.getPackageName() + ".SET_PARTY_SIZE")
                    .putExtra("ACTION_PARAM", "{\"adults\":3,\"children\":1}"));
            return null;
          });
      panel = field(activity, "destinationPanel");
      check(
          main(
              () -> (int) field(panel, "adultCount") == 3 && (int) field(panel, "childCount") == 1),
          "input-only counts not applied");
      check(
          main(
              () ->
                  model.destinationView().state().equals("EDITING")
                      && model.destinationView().candidates().length() == 0),
          "input-only started a search");
      check(journal.equals(journal()), "input-only changed reservation journal");
      capture("GLEO-input-only");
      main(
          () -> {
            activity.onNewIntent(
                new Intent("SET_PARTY_SIZE").putExtra("ACTION_PARAM", "{\"adults\":2}"));
            return null;
          });
      check(
          main(
              () -> (int) field(panel, "adultCount") == 3 && (int) field(panel, "childCount") == 1),
          "missing child count changed input");

      main(
          () -> {
            activity.onNewIntent(
                new Intent(ai.pleos.assistant.ux.GleoSearchCommand.ACTION)
                    .putExtra("ACTION_PARAM", "{\"adults\":2,\"children\":2}"));
            return null;
          });
      await(() -> model.destinationView().state().equals("RESULTS"), 15000);
      panel = field(activity, "destinationPanel");
      check(
          main(
              () -> (int) field(panel, "adultCount") == 2 && (int) field(panel, "childCount") == 2),
          "voice counts not applied");
      check(
          main(() -> model.destinationView().candidates().length() == 3),
          "voice MOCK results missing");
      capture("GLEO-valid-counts-results");
      main(
          () -> {
            activity.onNewIntent(
                new Intent(ai.pleos.assistant.ux.GleoSearchCommand.ACTION)
                    .putExtra("adults", 2)
                    .putExtra("children", 2));
            return null;
          });
      check(
          main(() -> model.destinationView().state().equals("RESULTS")),
          "duplicate restarted search");
      main(
          () -> {
            activity.onNewIntent(
                new Intent(ai.pleos.assistant.ux.GleoSearchCommand.ACTION)
                    .putExtra("ACTION_PARAM", "{\"adults\":2.5,\"children\":2}"));
            return null;
          });
      check(main(() -> (int) field(panel, "adultCount") == 2), "invalid voice mutated input");
      main(
          () -> {
            activity.debugRestriction();
            activity.onNewIntent(
                new Intent(ai.pleos.assistant.ux.GleoSearchCommand.ACTION)
                    .putExtra("adults", 3)
                    .putExtra("children", 0));
            return null;
          });
      check(
          main(() -> model.destinationView().candidates().length() == 0),
          "restricted voice retained result");
      check(main(() -> (int) field(panel, "adultCount") == 2), "restricted voice mutated input");
      capture("GLEO-restricted");
      main(
          () -> {
            activity.onNewIntent(
                new Intent("SET_PARTY_SIZE").putExtra("adults", 4).putExtra("children", 2));
            return null;
          });
      check(
          main(() -> (int) field(panel, "adultCount") == 2),
          "restricted input-only changed counts");

      main(
          () -> {
            activity.debugFailure();
            activity.onNewIntent(
                new Intent(ai.pleos.assistant.ux.GleoSearchCommand.ACTION)
                    .putExtra("adults", 3)
                    .putExtra("children", 0));
            return null;
          });
      check(main(() -> (int) field(panel, "adultCount") == 2), "unknown voice mutated input");
      check(journal.equals(journal()), "voice changed reservation journal");
      main(
          () -> {
            activity.retryDriving();
            return null;
          });
      Bundle status = new Bundle();
      status.putString(
          "stream",
          "PASS GLEO injected intents: counts/results/duplicate/invalid/restricted/unknown/journal; NOT actual Gleo voice\n");
      runner.sendStatus(0, status);
      return;
    }
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
