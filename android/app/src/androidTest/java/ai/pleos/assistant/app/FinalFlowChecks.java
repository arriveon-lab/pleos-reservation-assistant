package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Runs actual native buttons against the MOCK server; preserves installed legacy journals. */
final class FinalFlowChecks {
  static void run(Instrumentation runner, String scenario) throws Exception {
    if (!runner
        .getTargetContext()
        .getPackageName()
        .equals("ai.pleos.assistant.mock.designvalidation"))
      throw new AssertionError("dedicated design test installation required");
    var activity =
        (FinalReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), FinalReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(runner, () -> !activity.model.busy() && activity.model.restriction().allowsInput());
    if (main(runner, () -> activity.model.canCreateIdentity())) click(runner, activity, "connect");
    await(runner, () -> activity.model.canWrite());
    if (scenario.equals("FULL")) {
      if (activity.model.phone.hasRequest())
        throw new AssertionError("existing test record preserved; no new booking");
      VehicleUiChecks.capture(runner, "design-conditions");
      click(runner, activity, "search");
      await(runner, () -> activity.model.phoneTask() != null);
      VehicleUiChecks.capture(runner, "design-candidates");
      click(runner, activity, "select_1");
      VehicleUiChecks.capture(runner, "design-approval");
      if (activity.model.phone.hasRequest())
        throw new AssertionError("selection submitted approval");
      click(runner, activity, "other");
      if (activity.model.phone.hasRequest()) throw new AssertionError("back submitted approval");
      click(runner, activity, "select_1");
      click(runner, activity, "approve");
      await(runner, () -> !activity.model.phone.busy() && activity.model.phone.hasRequest());
      if (!activity.model.phone.state().equals("CONFIRMED"))
        throw new AssertionError("not confirmed: " + activity.model.phone.state());
      if (activity.model.phone.receipt().getInt("partySize") != 4
          || !activity.model.phone.receipt().getString("requestedTime").equals("19:10"))
        throw new AssertionError("approval mismatch");
    } else {
      await(runner, () -> activity.model.phone.hasRequest() && !activity.model.phone.busy());
      click(runner, activity, "refresh");
      await(runner, () -> !activity.model.phone.busy());
      if (!activity.model.phone.state().equals("CONFIRMED"))
        throw new AssertionError("restore failed");
    }
    if (activity.model.phone.receipt().getInt("externalCalls") != 0)
      throw new AssertionError("external calls");
    VehicleUiChecks.capture(runner, "design-result-" + scenario);
    click(runner, activity, "conversation");
    String text = main(runner, () -> text(activity.getWindow().getDecorView()));
    var turns = activity.model.phone.receipt().getJSONArray("conversation");
    if (turns.length() == 0) throw new AssertionError("no transcript");
    for (int i = 0; i < turns.length(); i++)
      if (!text.contains(turns.getJSONObject(i).getString("message")))
        throw new AssertionError("transcript mismatch");
    VehicleUiChecks.capture(runner, "design-conversation-" + scenario);
    click(runner, activity, "design_tools");
    if (main(runner, () -> find(activity.getWindow().getDecorView(), "change_accept").isEnabled()))
      throw new AssertionError("change enabled");
    click(runner, activity, "design_tools");
  }

  private static void click(Instrumentation r, FinalReservationActivity a, String tag)
      throws Exception {
    main(
        r,
        () -> {
          View v = find(a.getWindow().getDecorView(), tag);
          if (v == null || !v.isEnabled()) throw new AssertionError("button unavailable: " + tag);
          v.performClick();
          return null;
        });
    Thread.sleep(150);
  }

  private static View find(View v, String tag) {
    if (tag.equals(v.getTag())) return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View f = find(g.getChildAt(i), tag);
        if (f != null) return f;
      }
    return null;
  }

  private static String text(View v) {
    String s = v instanceof TextView t ? t.getText().toString() : "";
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) s += "\n" + text(g.getChildAt(i));
    return s;
  }

  private static <T> T main(Instrumentation r, Callable<T> c) throws Exception {
    FutureTask<T> f = new FutureTask<>(c);
    r.runOnMainSync(f);
    return f.get();
  }

  private static void await(Instrumentation r, Callable<Boolean> c) throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 35000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      if (main(r, c)) return;
      Thread.sleep(100);
    }
    throw new AssertionError("final UI timed out");
  }
}
