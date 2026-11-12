package ai.pleos.assistant.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;

/** Actual emulator UI navigation; responses are explicitly synthetic and do not call any API. */
public final class DemoFlowDeviceChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Activity activity = null;
    try {
      DemoFlowState unit = new DemoFlowState();
      unit.search();
      unit.select(0);
      unit.approve();
      unit.accepted();
      unit.callback();
      unit.decide(false);
      unit.reconfirm();
      check(
          unit.confirmedTime.equals("19:10") && unit.changeRejected,
          "Reject changed original time");
      unit.callback();
      unit.decide(true);
      unit.unknown();
      check(
          unit.confirmedTime.equals("19:10") && unit.stage == DemoFlowState.Stage.UNKNOWN,
          "Lost response changed time");
      activity =
          startActivitySync(
              new Intent(getTargetContext(), DemoFlowActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity a = activity;
      Field f = DemoFlowActivity.class.getDeclaredField("state");
      f.setAccessible(true);
      DemoFlowState state = (DemoFlowState) f.get(a);
      for (int i = 0; i < 40; i++) {
        waitForIdleSync();
        final boolean[] ready = {false};
        runOnMainSync(
            () -> {
              Button b =
                  find(
                      a.getWindow().getDecorView(),
                      getTargetContext().getString(R.string.flow_search));
              ready[0] = b != null && b.isEnabled();
            });
        if (ready[0]) break;
        Thread.sleep(250);
      }
      tap(a, R.string.flow_search);
      check(state.stage == DemoFlowState.Stage.CANDIDATES, "Candidates missing");
      tap(a, R.string.flow_select);
      check(state.stage == DemoFlowState.Stage.APPROVAL, "Approval missing");
      tap(a, R.string.flow_approve);
      check(state.stage == DemoFlowState.Stage.CALLING, "Call UI missing");
      check(state.confirmedTime.isEmpty(), "Premature confirmation");
      tap(a, R.string.flow_controls);
      tap(a, R.string.flow_sim_accept);
      check(state.stage == DemoFlowState.Stage.CONFIRMED, "Confirmation UI missing");
      tap(a, R.string.flow_sim_callback);
      check(state.stage == DemoFlowState.Stage.CHANGE_REQUEST, "Callback UI missing");
      tap(a, R.string.flow_accept_change);
      check(state.confirmedTime.equals("19:10"), "Premature time change");
      tap(a, R.string.flow_controls);
      tap(a, R.string.flow_sim_reconfirm);
      check(
          state.stage == DemoFlowState.Stage.CHANGED && state.confirmedTime.equals("19:30"),
          "Reconfirmation UI missing");
      Field allowed = DemoFlowActivity.class.getDeclaredField("allowed");
      allowed.setAccessible(true);
      var render = DemoFlowActivity.class.getDeclaredMethod("render");
      render.setAccessible(true);
      runOnMainSync(
          () -> {
            try {
              allowed.setBoolean(a, false);
              render.invoke(a);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      final boolean[] disabled = {false};
      runOnMainSync(
          () ->
              disabled[0] =
                  !find(
                          a.getWindow().getDecorView(),
                          getTargetContext().getString(R.string.flow_controls))
                      .isEnabled());
      check(disabled[0], "Restriction ignored");
      result.putString(
          "stream",
          "PASS: native UI conditions → selection → approval → simulated confirmation → incoming change → reconfirmation; reject/UNKNOWN preserve time; restrictions block input. Network/phone calls 0.\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("stream", "FAIL: " + error);
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (activity != null) {
        Activity a = activity;
        runOnMainSync(a::finish);
      }
    }
  }

  private void tap(Activity a, int resource) {
    runOnMainSync(
        () -> {
          Button b = find(a.getWindow().getDecorView(), getTargetContext().getString(resource));
          check(b != null && b.isEnabled(), "Button unavailable: " + resource);
          b.requestRectangleOnScreen(
              new android.graphics.Rect(0, 0, b.getWidth(), b.getHeight()), true);
          b.performClick();
        });
    waitForIdleSync();
  }

  private static Button find(View view, String text) {
    if (view instanceof Button b && text.equals(b.getText().toString())) return b;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
