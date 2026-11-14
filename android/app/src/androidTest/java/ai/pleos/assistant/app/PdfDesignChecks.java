package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.assistant.ux.MockRepository;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic presentation checks: paired auth is removed before any fixture action. */
final class PdfDesignChecks {
  static void run(Instrumentation runner, int width) throws Exception {
    FinalReservationActivity activity =
        (FinalReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), FinalReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    if (width > 0 && activity.getResources().getConfiguration().screenWidthDp != width) {
      var monitor = runner.addMonitor(FinalReservationActivity.class.getName(), null, false);
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
      activity = (FinalReservationActivity) runner.waitForMonitorWithTimeout(monitor, 5000);
      runner.removeMonitor(monitor);
      if (activity == null || activity.getResources().getConfiguration().screenWidthDp != width)
        throw new AssertionError("window resize mismatch");
    }
    final var a = activity;
    Thread.sleep(1500);
    runner.runOnMainSync(
        () -> {
          try {
            a.model.observe(null);
            set(a.model, "pairedAuth", null);
            set(a.model, "pairedJournal", null);
            set(a.model, "connected", true);
            set(a.model, "busy", false);
            set(a.model, "snapshot", new JSONObject());
            set(a.model, "restriction", MockRepository.Restriction.SIMULATED_PARKED);
            set(a.model, "destinationOpen", true);
            set(
                a.model,
                "navigationDestination",
                new DestinationController.Destination("서울숲 · 합성 검증", 37.5, 127.0, false, true));
            a.model.pairedError = "";
            a.model.pairedStatus =
                new JSONObject()
                    .put("active", true)
                    .put("mode", "LIVE")
                    .put("phoneLast4", "0000")
                    .put("expiresAt", "2099-01-01T00:00:00Z");
            set(a, "stage", "CONDITIONS");
            render(a);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    capture(runner, a, "conditions", "search");
    runner.runOnMainSync(
        () -> {
          try {
            Object destination = get(a.model, "destination");
            set(destination, "allowed", true);
            set(destination, "state", "RESULTS");
            set(destination, "taskId", "00000000-0000-0000-0000-000000000000");
            set(destination, "expires", Instant.parse("2099-01-01T00:00:00Z"));
            set(destination, "expiresNanos", Long.MAX_VALUE);
            JSONArray candidates = new JSONArray();
            String[] names = {"숲길 한상", "성수 키친", "뚝섬 가든"};
            for (int i = 0; i < 3; i++)
              candidates.put(
                  new JSONObject()
                      .put("ordinal", i + 1)
                      .put("name", names[i])
                      .put("address", "서울 성동구 · 합성 검증 주소")
                      .put("venueCategory", "RESTAURANT"));
            set(destination, "candidates", candidates);
            set(a, "stage", "CANDIDATES");
            render(a);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    capture(runner, a, "candidates", "edit");
    runner.runOnMainSync(
        () -> {
          try {
            var select =
                a.getClass().getDeclaredMethod("select", int.class, String.class, String.class);
            select.setAccessible(true);
            select.invoke(a, 1, "숲길 한상", "서울 성동구 · 합성 검증 주소");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    capture(runner, a, "approval", "approve");
    for (String status : new String[] {"CALLING", "UNKNOWN", "DECLINED", "CONFIRMED"}) {
      runner.runOnMainSync(
          () -> {
            try {
              JSONObject result =
                  new JSONObject()
                      .put("id", "00000000-0000-0000-0000-000000000000")
                      .put("mode", "LIVE")
                      .put("status", status)
                      .put("restaurantName", "숲길 한상 · 합성 검증")
                      .put("date", "2099-01-01")
                      .put("requestedTime", "19:10")
                      .put("partySize", 4);
              if (status.equals("CONFIRMED")) result.put("confirmedTime", "19:10");
              a.model.pairedStatus.put("latest", result);
              render(a);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      capture(runner, a, status.toLowerCase(), "paired_refresh");
    }
    runner.runOnMainSync(
        () -> {
          try {
            JSONObject callback =
                new JSONObject(a.model.pairedStatus.getJSONObject("latest").toString())
                    .put(
                        "change",
                        new JSONObject()
                            .put("id", "synthetic-change")
                            .put("version", 1)
                            .put("status", "AWAITING_USER")
                            .put("canDecide", true)
                            .put("originalTime", "19:10")
                            .put("proposedTime", "19:30")
                            .put("reason", "식당 사정으로 20분 늦은 입장을 요청했어요 · 합성 검증")
                            .put("additionalCostKrw", 0));
            a.model.pairedStatus.put("callback", callback);
            render(a);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    capture(runner, a, "callback", "live_callback_accept");
    runner.runOnMainSync(
        () -> {
          try {
            set(a.model, "restriction", MockRepository.Restriction.UNKNOWN);
            render(a);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    runner.runOnMainSync(
        () -> {
          Button accept = a.getWindow().getDecorView().findViewWithTag("live_callback_accept");
          if (accept.isEnabled()) throw new AssertionError("UNKNOWN permits change approval");
        });
    capture(runner, a, "restricted", "live_callback_accept");
    runner.runOnMainSync(a::finish);
  }

  private static void capture(
      Instrumentation runner, FinalReservationActivity a, String name, String action)
      throws Exception {
    runner.waitForIdleSync();
    Thread.sleep(250);
    runner.runOnMainSync(
        () -> {
          View button = a.getWindow().getDecorView().findViewWithTag(action);
          android.graphics.Rect visible = new android.graphics.Rect();
          if (button == null
              || !button.getGlobalVisibleRect(visible)
              || visible.height() < button.getHeight())
            throw new AssertionError("footer clipped: " + name);
        });
    VehicleUiChecks.capture(runner, "pdf-" + name);
  }

  private static Object get(Object target, String name) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static void render(FinalReservationActivity a) throws Exception {
    var m = a.getClass().getDeclaredMethod("render");
    m.setAccessible(true);
    m.invoke(a);
  }
}
