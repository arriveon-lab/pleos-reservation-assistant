package ai.pleos.assistant.app;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Presentation-only fixtures. Never clicks pairing, search, approval, or any outbound operation.
 */
final class PairedFinalUiChecks {
  static void run(Instrumentation runner) throws Exception {
    if (!BuildConfig.PAIRED_PHONE_DEMO) throw new AssertionError("paired build required");
    var night = LocalDateTime.of(2026, 11, 14, 23, 52, 30);
    var arrival = FinalReservationActivity.arrivalForToday(night, 0);
    if (!LocalDateTime.of(2026, 11, 14, 23, 55).equals(arrival)
        || FinalReservationActivity.arrivalForToday(night, 10) != null) {
      throw new AssertionError("late-night same-day test time is incorrect");
    }
    var a =
        (FinalReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), FinalReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Thread.sleep(1500);
    runner.runOnMainSync(
        () -> {
          try {
            a.model.observe(null);
            field(a.model, "connected", true);
            field(a.model, "busy", false);
            field(a.model, "destinationOpen", true);
            field(a.model, "pairedAuth", null); // prohibit network through paired API in fixtures
            var journal = runner.getTargetContext().getSharedPreferences("paired-reset-fixture", 0);
            journal.edit().clear().commit();
            field(a.model, "pairedJournal", journal);
            a.model.pairedStatus = new JSONObject();
            a.model.pairingCode = "0000000000";
            render(a);
            require(a, "시험 번호 연결");
            require(a, "0000000000");
            a.model.pairedStatus =
                new JSONObject()
                    .put("active", true)
                    .put("mode", "MOCK")
                    .put("phoneLast4", "0000")
                    .put("expiresAt", "2099-01-01T00:00:00Z");
            render(a);
            require(a, "가상 연결 해제");
            if (a.model.pairedLiveReady()) throw new AssertionError("MOCK accepted as LIVE");
            var r =
                new JSONObject()
                    .put("id", "00000000-0000-0000-0000-000000000000")
                    .put("mode", "LIVE")
                    .put("status", "UNKNOWN")
                    .put("restaurantName", "가상 검증 식당")
                    .put("date", "2099-01-01")
                    .put("requestedTime", "19:10")
                    .put("partySize", 4)
                    .put("notice", "미확인")
                    .put("requirements", new JSONObject());
            a.model.pairedStatus =
                new JSONObject()
                    .put("active", true)
                    .put("expiresAt", "2099-01-01T00:00:00Z")
                    .put("mode", "LIVE")
                    .put("phoneLast4", "0000")
                    .put("latest", r);
            render(a);
            require(a, "UNKNOWN");
            if (a.model.startNewPairedDemo()) throw new AssertionError("UNKNOWN reset allowed");
            if (text(a.getWindow().getDecorView()).contains("예약 확정 응답"))
              throw new AssertionError("unknown promoted");
            r.put("status", "CONFIRMED").put("confirmedTime", "19:10");
            render(a);
            require(a, "예약 확정 응답");
            require(a, "19:10");
            var liveChange =
                new JSONObject()
                    .put("id", "00000000000000000000000000000000")
                    .put("version", 2)
                    .put("status", "AWAITING_USER")
                    .put("canDecide", true)
                    .put("originalTime", "19:10")
                    .put("proposedTime", "19:30")
                    .put("reason", "가상 지연")
                    .put("additionalCostKrw", 0);
            var callback = new JSONObject(r.toString()).put("change", liveChange);
            a.model.pairedStatus.put("callback", callback);
            render(a);
            AlertDialog request = dialog(a, "callbackChangeDialog");
            if (request == null || !request.isShowing())
              throw new AssertionError("callback popup missing");
            String proposal = text(request.getWindow().getDecorView());
            if (!proposal.contains("19:10 예약 → 19:30 변경 요청")
                || !proposal.contains("새 시간 수락")
                || !proposal.contains("변경 거절"))
              throw new AssertionError("callback popup missing proposal or actions");
            render(a);
            if (request != dialog(a, "callbackChangeDialog"))
              throw new AssertionError("status polling replaced the callback popup");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    Thread.sleep(300);
    VehicleUiChecks.capture(runner, "paired-callback-popup");
    runner.runOnMainSync(
        () -> {
          try {
            JSONObject callback = a.model.pairedStatus.getJSONObject("callback");
            JSONObject liveChange = callback.getJSONObject("change");
            liveChange.put("status", "RECONFIRMING").put("canDecide", false);
            render(a);
            require(a, "식당에 새 시간을 확인 중");
            if (dialog(a, "callbackChangeDialog") != null)
              throw new AssertionError("decision remained available");
            liveChange.put("status", "AWAITING_USER").put("canDecide", true);
            render(a);
            if (dialog(a, "callbackChangeDialog") == null)
              throw new AssertionError("new pending request was not displayed");
            liveChange.put("status", "EXPIRED").put("canDecide", false);
            render(a);
            if (dialog(a, "callbackChangeDialog") != null)
              throw new AssertionError("expired callback popup remained visible");
            a.model.pairedStatus.remove("callback");
            render(a);
            click(a, "design_tools");
            click(a, "traffic_delay_demo");
            require(a, "도착 예정 18:55 · 교통 시연");
            if (dialog(a) != null) throw new AssertionError("proposal shown before ETA update");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
    var shown = new AtomicBoolean();
    while (!shown.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
      runner.runOnMainSync(
          () -> {
            AlertDialog alert = dialog(a);
            shown.set(alert != null && alert.isShowing());
          });
      if (!shown.get()) Thread.sleep(100);
    }
    if (!shown.get()) throw new AssertionError("traffic proposal did not appear automatically");
    Thread.sleep(300); // Allow the dialog to render before taking the screenshot.
    VehicleUiChecks.capture(runner, "traffic-delay-proposal");
    var originalState = new String[1];
    runner.runOnMainSync(
        () -> {
          try {
            AlertDialog alert = dialog(a);
            String alertText = text(alert.getWindow().getDecorView());
            if (!alertText.contains("18:55 → 19:20")
                || !alertText.contains("19:30")
                || !alertText.contains("교통 지연 시연")
                || !alertText.contains("예약보다 10분 늦게 도착해요"))
              throw new AssertionError("traffic proposal times or simulation label missing");
            originalState[0] = a.model.pairedStatus.toString();
            alert.getWindow().getDecorView().findViewWithTag("traffic_inquire").performClick();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    runner.waitForIdleSync();
    runner.runOnMainSync(
        () -> {
          try {
            require(a, "19:30 변경 문의 선택 · 시연");
            if (!originalState[0].equals(a.model.pairedStatus.toString()))
              throw new AssertionError("traffic proposal changed server result");
            require(a, "도착 예정 19:20 · 교통 시연");
            var r = a.model.pairedStatus.getJSONObject("latest");
            if (!"19:10".equals(r.getString("confirmedTime")))
              throw new AssertionError("traffic demo rewrote confirmed time");
            r.put("status", "UNKNOWN");
            render(a);
            click(a, "design_tools");
            if (a.getWindow().getDecorView().findViewWithTag("traffic_delay_demo") != null)
              throw new AssertionError("UNKNOWN traffic proposal enabled");
            click(a, "design_tools");
            r.put("status", "CONFIRMED");
            var journal = runner.getTargetContext().getSharedPreferences("paired-reset-fixture", 0);
            journal
                .edit()
                .putString("attempt", new JSONObject().put("key", r.getString("id")).toString())
                .commit();
            if (!a.model.startNewPairedDemo()) throw new AssertionError("completed reset blocked");
            if (a.model.pairedAttempted()) throw new AssertionError("old approval remains");
            // A late status response or the first poll after restart must not reopen the old
            // result.
            a.model.pairedStatus.put("latest", r);
            var hide = a.model.getClass().getDeclaredMethod("hideCompletedDemo");
            hide.setAccessible(true);
            hide.invoke(a.model);
            if (a.model.pairedStatus.has("latest"))
              throw new AssertionError("completed result returned");
            var edit = a.getClass().getDeclaredMethod("edit");
            edit.setAccessible(true);
            edit.invoke(a);
            require(a, "이 조건으로 주변 식당");
            require(a, "현재 이동시간을 확인할 수 없어요");
            require(a, "예약 희망 시각");
            r.put("id", "another-attempt").put("status", "UNKNOWN");
            a.model.pairedStatus.put("latest", r);
            hide.invoke(a.model);
            if (!a.model.pairedStatus.has("latest")) throw new AssertionError("new result hidden");
            journal.edit().clear().commit();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    VehicleUiChecks.capture(runner, "paired-final-fixture");
    runner.runOnMainSync(a::finish);
  }

  private static AlertDialog dialog(FinalReservationActivity a) {
    return dialog(a, "demoChangeDialog");
  }

  private static AlertDialog dialog(FinalReservationActivity a, String name) {
    try {
      var field = a.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return (AlertDialog) field.get(a);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void click(FinalReservationActivity a, String tag) {
    View button = a.getWindow().getDecorView().findViewWithTag(tag);
    if (button == null || !button.isEnabled())
      throw new AssertionError("button unavailable: " + tag);
    button.performClick();
  }

  private static void field(Object target, String name, Object value) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static void render(FinalReservationActivity a) throws Exception {
    var m = a.getClass().getDeclaredMethod("render");
    m.setAccessible(true);
    m.invoke(a);
  }

  private static void require(FinalReservationActivity a, String expected) {
    if (!text(a.getWindow().getDecorView()).contains(expected)) throw new AssertionError(expected);
  }

  private static String text(View v) {
    String s = v instanceof TextView t ? t.getText().toString() : "";
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) s += "\n" + text(g.getChildAt(i));
    return s;
  }
}
