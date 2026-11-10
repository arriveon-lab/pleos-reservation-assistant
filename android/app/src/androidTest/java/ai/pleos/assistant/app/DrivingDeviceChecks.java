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
  private int windowWidth;
  private boolean dashboard;
  private boolean previewBoundary;
  private boolean recoveryFailure;
  private String searchRecovery;
  private float expectedFontScale;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    windowWidth =
        Integer.parseInt(
            arguments.getString(
                "windowWidth", "true".equals(arguments.getString("narrow")) ? "840" : "0"));
    if (windowWidth != 0 && (windowWidth < 600 || windowWidth > 1682))
      throw new IllegalArgumentException("Unsupported test window width");
    previewBoundary = "true".equals(arguments.getString("previewBoundary"));
    expectedFontScale = Float.parseFloat(arguments.getString("fontScale", "0"));
    recoveryFailure = "true".equals(arguments.getString("recoveryFailure"));
    searchRecovery = arguments.getString("searchRecovery", "");
    dashboard = "true".equals(arguments.getString("dashboard"));
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      if (!searchRecovery.isEmpty()) {
        SearchRecoveryDeviceChecks.run(this, "restart".equals(searchRecovery));
        result.putString(
            "stream", "PASS: existing installation search recovery " + searchRecovery + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (recoveryFailure) {
        RecoveryDeviceChecks.run(this);
        result.putString("stream", "PASS: injected recovery transport and UI checks\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (previewBoundary) {
        PreviewBoundaryChecks.run(this);
        result.putString(
            "stream", "PASS: preview boundary and real five-minute expiry device checks\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      Intent intent =
          new Intent(getTargetContext(), ReservationActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ReservationActivity initial = (ReservationActivity) startActivitySync(intent);
      ReservationActivity activity;
      if (windowWidth > 0
          && initial.getResources().getConfiguration().screenWidthDp != windowWidth) {
        ActivityMonitor resize = addMonitor(ReservationActivity.class.getName(), null, false);
        try (android.os.ParcelFileDescriptor resultFd =
                getUiAutomation()
                    .executeShellCommand(
                        "am task resize "
                            + initial.getTaskId()
                            + " "
                            + (2535 - windowWidth)
                            + " 90 2535 1300");
            java.io.InputStream input =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(resultFd)) {
          input.readAllBytes();
        }
        activity = (ReservationActivity) waitForMonitorWithTimeout(resize, 5000);
        removeMonitor(resize);
        check(
            activity != null
                && activity.getResources().getConfiguration().screenWidthDp == windowWidth,
            "requested window width not applied");
      } else activity = initial;
      if (expectedFontScale > 0) {
        check(
            Math.abs(activity.getResources().getConfiguration().fontScale - expectedFontScale)
                < 0.01f,
            "requested font scale not applied to Activity");
      }
      ReservationViewModel model = (ReservationViewModel) field(activity, "model");
      await(() -> dashboard ? model.connected() && !model.busy() : model.canWrite());
      waitForIdleSync();
      if (dashboard) {
        DashboardChecks.run(this, activity, model);
        result.putString("stream", "PASS: dashboard layout, states and input checks\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      runOnMainSync(() -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
      runOnMainSync(() -> VehicleUiChecks.resultStates(activity, model));
      waitForIdleSync();
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
                  activity.getString(
                      R.string.approve_body, "가상 성수 가족식당 · 긴 한국어 표시 검사", 2, 1, "11-09 18:30 KST"),
                  (ReservationViewModel.Action) repository -> invoked.set(true));
              AlertDialog dialog = (AlertDialog) field(activity, "approval");
              check(dialog.isShowing(), "approval not shown");
            } catch (ReflectiveOperationException error) {
              throw new RuntimeException(error);
            }
          });
      waitForIdleSync();
      AlertDialog shown = (AlertDialog) field(activity, "approval");
      runOnMainSync(() -> VehicleUiChecks.layout(shown.getWindow().getDecorView()));
      // Idle main queue does not imply that the window opening animation has drawn.
      Thread.sleep(500);
      waitForIdleSync();
      runOnMainSync(() -> check(shown.isShowing(), "approval disappeared before capture"));
      VehicleUiChecks.capture(this, "approval-layout");
      runOnMainSync(
          () -> {
            activity.debugRestriction();
            check(!shown.isShowing(), "restricted dialog remained");
            check(!model.canWrite(), "restriction did not block input");
            shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
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
      runOnMainSync(() -> VehicleUiChecks.prepareDraft(reopened));
      ActivityMonitor monitor = addMonitor(ReservationActivity.class.getName(), null, false);
      runOnMainSync(reopened::recreate);
      ReservationActivity recreated =
          (ReservationActivity) waitForMonitorWithTimeout(monitor, 5000);
      removeMonitor(monitor);
      check(recreated != null && recreated != reopened, "activity recreation failed");
      ReservationViewModel retained = (ReservationViewModel) field(recreated, "model");
      await(retained::canWrite);
      runOnMainSync(() -> VehicleUiChecks.checkDraft(recreated));
      try {
        new LoopbackTransport(() -> false)
            .exchange("/searches", new org.json.JSONObject(), "blocked-test", null);
        throw new AssertionError("transport dispatched while blocked");
      } catch (IllegalStateException expected) {
        check("INPUT_BLOCKED".equals(expected.getMessage()), "unexpected transport rejection");
      }
      result.putString(
          "stream",
          "PASS: fontScale="
              + activity.getResources().getConfiguration().fontScale
              + "; actual baseline read/reopen/recreate; measured UI/dialog layout; injected result-state display; transport gate; injected restriction/failure; "
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
