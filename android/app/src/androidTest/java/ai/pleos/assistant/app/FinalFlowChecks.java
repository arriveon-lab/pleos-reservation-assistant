package ai.pleos.assistant.app;

import android.app.AlertDialog;
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
      if (BuildConfig.FILMING_DEMO) click(runner, activity, "chair");
      VehicleUiChecks.capture(runner, "design-conditions");
      click(runner, activity, "search");
      await(
          runner,
          () -> {
            View button = find(activity.getWindow().getDecorView(), "select_1");
            return button != null && button.isEnabled();
          });
      if (BuildConfig.FILMING_DEMO) {
        String candidates = main(runner, () -> text(activity.getWindow().getDecorView()));
        if (!candidates.contains("서울숲 가족식당")
            || !candidates.contains("유아의자 있음")
            || candidates.contains("가상 공공데이터 식당"))
          throw new AssertionError("filming candidates not rendered");
      }
      VehicleUiChecks.capture(runner, "design-candidates");
      click(runner, activity, "select_1");
      VehicleUiChecks.capture(runner, "design-approval");
      if (activity.model.phone.hasRequest())
        throw new AssertionError("selection submitted approval");
      click(runner, activity, "other");
      if (activity.model.phone.hasRequest()) throw new AssertionError("back submitted approval");
      click(runner, activity, "select_1");
      String approval = main(runner, () -> text(activity.getWindow().getDecorView()));
      click(runner, activity, "approve");
      await(runner, () -> !activity.model.phone.busy() && activity.model.phone.hasRequest());
      if (!activity.model.phone.state().equals("CONFIRMED"))
        throw new AssertionError("not confirmed: " + activity.model.phone.state());
      var receipt = activity.model.phone.receipt();
      if (receipt.getInt("partySize") != (BuildConfig.FILMING_DEMO ? 3 : 4)
          || !approval.contains(receipt.getString("requestedTime")))
        throw new AssertionError("approval mismatch");
      if (BuildConfig.FILMING_DEMO) {
        var date = java.time.LocalDate.parse(receipt.getString("date"));
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        if (!(date.equals(today) && approval.contains("오늘")
            || date.equals(today.plusDays(1)) && approval.contains("내일")))
          throw new AssertionError("visible reservation day differs from requested day");
      }
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
    click(runner, activity, "conversation");
    if (!BuildConfig.FILMING_DEMO) {
      click(runner, activity, "design_tools");
      if (main(
          runner, () -> find(activity.getWindow().getDecorView(), "change_accept").isEnabled()))
        throw new AssertionError("change enabled");
    }
    String original = activity.model.phone.receipt().getString("confirmedTime");
    click(runner, activity, "traffic_delay_demo");
    var dialogField = FinalReservationActivity.class.getDeclaredField("demoChangeDialog");
    dialogField.setAccessible(true);
    await(
        runner, () -> dialogField.get(activity) instanceof AlertDialog alert && alert.isShowing());
    Thread.sleep(300); // Allow the dialog to render before taking the screenshot.
    VehicleUiChecks.capture(runner, "traffic-delay-mock");
    main(
        runner,
        () -> {
          var alert = (AlertDialog) dialogField.get(activity);
          if (!text(alert.getWindow().getDecorView()).contains("예약보다 10분 늦게 도착해요"))
            throw new AssertionError("traffic delay is unclear");
          find(
                  alert.getWindow().getDecorView(),
                  BuildConfig.FILMING_DEMO ? "traffic_inquire" : "traffic_keep")
              .performClick();
          return null;
        });
    await(
        runner,
        () ->
            text(activity.getWindow().getDecorView())
                .contains(
                    BuildConfig.FILMING_DEMO
                        ? "변경 문의 선택 · 시연"
                        : "기존 시간 " + original + " 유지를 선택했어요"));
    if (!original.equals(activity.model.phone.receipt().getString("confirmedTime")))
      throw new AssertionError("traffic simulation changed the MOCK reservation");
    VehicleUiChecks.capture(runner, "traffic-delay-outcome");
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
