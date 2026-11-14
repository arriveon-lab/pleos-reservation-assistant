package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.MockRepository;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

/** Local synthetic HTTP flow only; never evidence of separate operator authorization. */
final class RebookingDeviceChecks {
  static void run(DrivingDeviceChecks runner) throws Exception {
    ReservationActivity activity =
        (ReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), ReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ReservationViewModel model = (ReservationViewModel) field(activity, "model").get(activity);
    await(() -> model.canWrite());
    MockRepository.Journal journal =
        new MockRepository.Journal() {
          String saved;

          public String read() {
            return saved;
          }

          public void save(String value) {
            saved = value;
          }
        };
    MockRepository repo = new MockRepository(RebookingDeviceChecks::exchange, journal);
    repo.restrict(model.restriction());
    repo.connect();
    repo.search(2, 1, true);
    repo.inquire("mock-1");
    repo.simulate("ANSWERED");
    awaitBookable(repo);
    repo.book("mock-1", true);
    repo.simulate("REJECTED");
    String rejectedId = repo.snapshot().getString("reservationId");
    Field repository = field(model, "repository"), snapshot = field(model, "snapshot");
    Object originalRepository = repository.get(model), originalSnapshot = snapshot.get(model);
    try {
      main(
          runner,
          () -> {
            repository.set(model, repo);
            snapshot.set(model, repo.snapshot());
            render(activity);
          });
      click(runner, activity, R.string.rebooking_start);
      main(
          runner,
          () -> {
            AlertDialog dialog = (AlertDialog) field(activity, "approval").get(activity);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          });
      await(() -> !model.busy() && !model.failed() && model.snapshot().has("rejectedReservations"));
      if (repo.snapshot().has("searchId"))
        throw new AssertionError("Preparation automatically searched");
      click(runner, activity, R.string.search);
      await(() -> !model.busy() && model.snapshot().has("search"));
      click(runner, activity, R.string.inquire);
      await(() -> !model.busy() && model.snapshot().has("call"));
      repo.simulate("ANSWERED");
      awaitBookable(repo);
      main(runner, () -> model.run(MockRepository::refresh));
      await(
          () ->
              !model.busy()
                  && "ANSWERED".equals(model.snapshot().getJSONObject("call").optString("status")));
      click(runner, activity, R.string.book);
      if (repo.snapshot().has("reservationId")) throw new AssertionError("Booking before approval");
      main(
          runner,
          () -> {
            AlertDialog dialog = (AlertDialog) field(activity, "approval").get(activity);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          });
      await(() -> !model.busy() && model.snapshot().has("reservationId"));
      repo.simulate("ACCEPTED");
      main(runner, () -> model.run(MockRepository::refresh));
      await(
          () ->
              !model.busy()
                  && "CONFIRMED"
                      .equals(model.snapshot().getJSONObject("reservation").optString("status")));
      if (!rejectedId.equals(
          repo.snapshot()
              .getJSONArray("rejectedReservations")
              .getJSONObject(0)
              .getString("reservationId"))) throw new AssertionError("Rejected history lost");
      runner.waitForIdleSync();
      Thread.sleep(500);
      main(runner, () -> VehicleUiChecks.layout(activity.getWindow().getDecorView()));
      VehicleUiChecks.capture(runner, "rebooking-confirmed");
    } finally {
      await(() -> !model.busy());
      main(
          runner,
          () -> {
            repository.set(model, originalRepository);
            snapshot.set(model, originalSnapshot);
            render(activity);
            activity.finish();
          });
    }
  }

  private static void awaitBookable(MockRepository repo) throws Exception {
    // Accepted observations can be slightly ahead of server time (up to five seconds).
    // Re-read facts after that time; never rewrite their observedAt or dispatch another inquiry.
    await(
        () -> {
          repo.refresh();
          return repo.candidate(repo.snapshot().getJSONObject("call").getString("candidateId"))
              .getBoolean("bookable");
        });
  }

  private static JSONObject exchange(String path, JSONObject body, String key) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL("http://127.0.0.1:8082/api/v1" + path).openConnection(java.net.Proxy.NO_PROXY);
    try {
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        try (var output = connection.getOutputStream()) {
          output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      if (connection.getResponseCode() != 200)
        throw new AssertionError("Fixture HTTP " + connection.getResponseCode());
      try (var input = connection.getInputStream()) {
        return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  private static void click(DrivingDeviceChecks runner, ReservationActivity activity, int label)
      throws Exception {
    runner.waitForIdleSync();
    Thread.sleep(300);
    main(
        runner,
        () -> {
          Button button = find(activity.getWindow().getDecorView(), activity.getString(label));
          if (button == null || !button.isEnabled())
            throw new AssertionError("Action unavailable: " + label);
          button.performClick();
        });
  }

  private static Button find(View view, String label) {
    if (view instanceof Button button && label.contentEquals(button.getText())) return button;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static Field field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void render(ReservationActivity activity) throws Exception {
    var method = ReservationActivity.class.getDeclaredMethod("render");
    method.setAccessible(true);
    method.invoke(activity);
  }

  private interface Check {
    boolean get() throws Exception;
  }

  private interface Action {
    void run() throws Exception;
  }

  private static void await(Check check) throws Exception {
    for (int i = 0; i < 150; i++) {
      if (check.get()) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Rebooking condition timed out");
  }

  private static void main(DrivingDeviceChecks runner, Action action) throws Exception {
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              action.run();
              return null;
            });
    runner.runOnMainSync(task);
    task.get();
  }
}
