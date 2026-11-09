package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DrivingRestrictionController;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit device check: requires automotive baseline and a connected MOCK test installation. */
public final class DrivingDeviceChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      Intent intent =
          new Intent(getTargetContext(), ReservationActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ReservationActivity activity = (ReservationActivity) startActivitySync(intent);
      ReservationViewModel model = (ReservationViewModel) field(activity, "model");
      await(() -> model.canWrite());
      AtomicBoolean invoked = new AtomicBoolean();
      Method confirm =
          ReservationActivity.class.getDeclaredMethod(
              "confirm", int.class, String.class, ReservationViewModel.Action.class);
      confirm.setAccessible(true);
      runOnMainSync(
          () -> {
            try {
              confirm.invoke(
                  activity,
                  R.string.approve_title,
                  "MOCK 승인 차단 검사",
                  (ReservationViewModel.Action) repository -> invoked.set(true));
              AlertDialog dialog = (AlertDialog) field(activity, "approval");
              check(dialog.isShowing(), "approval not shown");
              activity.debugRestriction();
              check(!dialog.isShowing(), "restricted dialog remained");
              check(!model.canWrite(), "restriction did not block input");
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            } catch (ReflectiveOperationException error) {
              throw new RuntimeException(error);
            }
          });
      waitForIdleSync();
      check(!invoked.get(), "stale approval dispatched");
      runOnMainSync(activity::debugFailure);
      check(
          model.driving().reason() == DrivingRestrictionController.Reason.DEBUG_FAILURE,
          "injected failure missing");
      check(!model.canWrite(), "failure allowed input");
      runOnMainSync(activity::retryDriving);
      await(model::canWrite);
      runOnMainSync(activity::finish);
      waitForIdleSync();
      await(() -> model.driving().reason() == DrivingRestrictionController.Reason.STOPPED);
      check(!model.canWrite(), "stopped activity retained permission");
      ReservationActivity reopened = (ReservationActivity) startActivitySync(intent);
      ReservationViewModel next = (ReservationViewModel) field(reopened, "model");
      await(next::canWrite);
      ActivityMonitor monitor = addMonitor(ReservationActivity.class.getName(), null, false);
      runOnMainSync(reopened::recreate);
      ReservationActivity recreated =
          (ReservationActivity) waitForMonitorWithTimeout(monitor, 5000);
      removeMonitor(monitor);
      check(recreated != null && recreated != reopened, "activity recreation failed");
      ReservationViewModel retained = (ReservationViewModel) field(recreated, "model");
      await(retained::canWrite);
      try {
        new LoopbackTransport(() -> false)
            .exchange("/searches", new org.json.JSONObject(), "blocked-test", null);
        throw new AssertionError("transport dispatched while blocked");
      } catch (IllegalStateException expected) {
        check("INPUT_BLOCKED".equals(expected.getMessage()), "unexpected transport rejection");
      }
      result.putString(
          "stream",
          "PASS: actual baseline read/reopen/recreate; transport gate; injected restriction/failure; "
              + "approval dismissed and stale click blocked; stop invalidates permission. "
              + "Real moving callback and real service failure NOT tested.\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString(
          "stream", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      AtomicBoolean value = new AtomicBoolean();
      runOnMainSync(() -> value.set(condition.getAsBoolean()));
      if (value.get()) return;
      Thread.sleep(100);
    }
    throw new AssertionError("device precondition/state timed out");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
