package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic phone contract on an actual emulator; never uses the deployed telephone service. */
public final class PhoneTrialDeviceChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  private static final class Vault implements AuthSession.Vault {
    String value;

    public String read() {
      return value;
    }

    public void save(String next) {
      value = next;
    }
  }

  private static final class Fixture implements PhoneTrialRepository.Wire {
    JSONObject booking;
    boolean lost;
    boolean rejectRegistration;
    String key;
    int createAttempts;
    int dialAttempts;

    public JSONObject exchange(String path, JSONObject body, JSONObject session) throws Exception {
      if (path.equals("/api/live/dial")) {
        dialAttempts++;
        throw new java.io.IOException("DIAL_RESPONSE_LOST");
      }
      if (path.equals("/api/state")) {
        session.put("cookie", "phone_test_session_18767=" + "a".repeat(64));
        JSONObject result =
            new JSONObject()
                .put(
                    "testRestaurants",
                    new JSONArray()
                        .put(new JSONObject().put("id", "test-fixture").put("name", "가상 전화 시험 식당")))
                .put(
                    "trips",
                    new JSONArray().put(new JSONObject().put("id", "trip").put("version", 1)));
        result.put(
            "voice",
            new JSONObject().put("ready", true).put("testPhoneInput", true).put("maxSeconds", 60));
        result.put("bookings", booking == null ? new JSONArray() : new JSONArray().put(booking));
        result.put(
            "events",
            booking == null
                ? new JSONArray()
                : new JSONArray()
                    .put(
                        new JSONObject()
                            .put("bookingId", "booking")
                            .put("message", "가상 시험 대화: 예약을 확인했습니다.")));
        return result;
      }
      if (path.equals("/api/live/callback-test-target") && body != null && rejectRegistration)
        throw new PhoneTrialRepository.Rejected("CALLBACK_TEST_ACTIVE");
      if (path.equals("/api/live/callback-test-target"))
        return new JSONObject()
            .put("active", true)
            .put("phoneLast4", "0000")
            .put("expiresAt", "2099-01-01T00:00:00Z");
      if (!path.equals("/api/action")) throw new AssertionError("Unexpected external path");
      if (body.getString("action").equals("create_booking")) {
        String next = body.getString("requestId");
        if (key != null && !key.equals(next)) throw new AssertionError("Changed retry key");
        key = next;
        createAttempts++;
        booking =
            new JSONObject()
                .put("id", "booking")
                .put("version", 1)
                .put("restaurantName", "가상 전화 시험 식당")
                .put("status", "AWAITING_APPROVAL")
                .put("liveTest", new JSONObject().put("id", "test-fixture"))
                .put("date", "2099-01-01")
                .put("requestedTime", "19:00")
                .put("snapshot", new JSONObject().put("partySize", 2));
        if (lost) {
          lost = false;
          throw new java.io.IOException("LOST_RESPONSE");
        }
      } else if (body.getString("action").equals("approve_booking")) {
        booking.put("status", "READY");
      } else throw new AssertionError("Unexpected action");
      return new JSONObject();
    }
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Activity activity = null;
    try {
      Vault vault = new Vault();
      Fixture fixture = new Fixture();
      PhoneTrialRepository repo = new PhoneTrialRepository(vault, fixture);
      repo.login("fixture", "not-a-secret");
      fixture.rejectRegistration = true;
      try {
        repo.register("01000000000");
        throw new AssertionError("Missing registration rejection");
      } catch (PhoneTrialRepository.Rejected expected) {
      }
      if (repo.pending() || !new JSONObject(vault.value).has("lastRejected"))
        throw new AssertionError("Rejected request recovery");
      fixture.rejectRegistration = false;
      repo.register("01000000000");
      fixture.lost = true;
      try {
        repo.action("create_booking", new JSONObject());
        throw new AssertionError("Missing failure");
      } catch (java.io.IOException expected) {
      }
      if (!repo.pending()) throw new AssertionError("Lost journal");
      PhoneTrialRepository restored = new PhoneTrialRepository(vault, fixture);
      restored.retry();
      if (restored.pending() || fixture.createAttempts != 2)
        throw new AssertionError("Retry recovery");
      restored.action(
          "approve_booking", new JSONObject().put("bookingId", "booking").put("version", 1));
      try {
        restored.action("run_call", new JSONObject());
        throw new AssertionError("Dial allowed");
      } catch (IllegalStateException expected) {
      }
      PhoneTrialRepository dialRepo = new PhoneTrialRepository(vault, fixture, true);
      try {
        dialRepo.dial("booking", 1, "01000000000");
        throw new AssertionError("Missing dial timeout");
      } catch (java.io.IOException expected) {
      }
      PhoneTrialRepository restoredDial = new PhoneTrialRepository(vault, fixture, true);
      try {
        restoredDial.dial("booking", 1, "01000000000");
        throw new AssertionError("Redial allowed");
      } catch (IllegalStateException expected) {
      }
      if (fixture.dialAttempts != 1 || !restoredDial.dialAttempted())
        throw new AssertionError("Lost call marker");
      activity =
          startActivitySync(
              new Intent(getTargetContext(), PhoneTrialActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity current = activity;
      Field field = PhoneTrialActivity.class.getDeclaredField("model");
      field.setAccessible(true);
      PhoneTrialViewModel model = (PhoneTrialViewModel) field.get(current);
      runOnMainSync(
          () -> {
            model.repository = restored;
            model.allowed = true;
            model.notifyView();
          });
      waitForIdleSync();
      List<View> views = new ArrayList<>();
      runOnMainSync(() -> collect(current.getWindow().getDecorView(), views));
      Button dial =
          views.stream()
              .filter(v -> v instanceof Button b && b.getText().toString().contains("발신 잠금"))
              .map(v -> (Button) v)
              .findFirst()
              .orElseThrow();
      if (dial.isEnabled()) throw new AssertionError("Dial must be locked");
      fixture.booking.put("status", "CONFIRMED").put("confirmedTime", "19:00");
      restored.refresh();
      runOnMainSync(model::notifyView);
      waitForIdleSync();
      views.clear();
      runOnMainSync(() -> collect(current.getWindow().getDecorView(), views));
      if (views.stream()
          .noneMatch(
              v ->
                  v instanceof android.widget.TextView t
                      && t.getText().toString().contains("시험 예약 확정")))
        throw new AssertionError("Missing result");
      runOnMainSync(
          () -> {
            model.allowed = false;
            model.notifyView();
          });
      views.clear();
      runOnMainSync(() -> collect(current.getWindow().getDecorView(), views));
      if (views.stream().anyMatch(v -> v instanceof android.widget.EditText))
        throw new AssertionError("Restricted input");
      result.putString(
          "stream",
          "PASS: isolated cookie/journal, lost response same-key retry, approval, dial blocked, synthetic result UI; no external calls\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable failure) {
      result.putString(
          "stream", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (activity != null) {
        Activity a = activity;
        runOnMainSync(a::finish);
      }
    }
  }

  private static void collect(View view, List<View> result) {
    result.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), result);
  }
}
