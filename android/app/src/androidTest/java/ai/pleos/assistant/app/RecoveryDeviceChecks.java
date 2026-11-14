package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

/**
 * Explicit fault-injection check. Requires the local recovery HTTP fixture, not a product server.
 */
final class RecoveryDeviceChecks {
  static void run(DrivingDeviceChecks runner) throws Exception {
    LoopbackTransport wire = new LoopbackTransport(() -> true);
    expect(wire, "/searches", 400, "INVALID_TIME");
    expect(wire, "/experimental/place-previews/fixture/close", 404, "PREVIEW_NOT_FOUND");
    expect(wire, "/calls", 404, "");
    expect(wire, "/reservations", 503, "");
    ReservationActivity activity =
        (ReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), ReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Thread.sleep(1000);
    FutureTask<Void> render =
        new FutureTask<>(
            () -> {
              ReservationViewModel model =
                  (ReservationViewModel) field(activity, "model").get(activity);
              Field snapshot = field(model, "snapshot");
              Object original = snapshot.get(model);
              try {
                snapshot.set(
                    model,
                    new JSONObject()
                        .put("pending", new JSONObject().put("recoveryCode", "INVALID_TIME")));
                var method = ReservationActivity.class.getDeclaredMethod("render");
                method.setAccessible(true);
                method.invoke(activity);
                if (!contains(
                    activity.getWindow().getDecorView(),
                    activity.getString(R.string.pending_time_rejected)))
                  throw new AssertionError("time rejection guidance missing");
                if (model.canWrite()) throw new AssertionError("pending allowed new command");
              } finally {
                // The fixture only changes the displayed snapshot; never write the durable journal.
                snapshot.set(model, original);
              }
              return null;
            });
    runner.runOnMainSync(render);
    render.get();
    runner.waitForIdleSync();
    Thread.sleep(500);
    VehicleUiChecks.capture(runner, "recovery-time-rejected");
    runner.runOnMainSync(activity::finish);
  }

  private static void expect(LoopbackTransport wire, String path, int status, String code)
      throws Exception {
    try {
      wire.exchange(path, new JSONObject(), "fixture-key", null);
      throw new AssertionError("expected HTTP failure");
    } catch (AuthSession.HttpFailure error) {
      if (error.status != status || !code.equals(error.code))
        throw new AssertionError("incorrect safe error classification");
    }
  }

  private static Field field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static boolean contains(View view, String expected) {
    if (view instanceof TextView text && expected.contentEquals(text.getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), expected)) return true;
    return false;
  }
}
