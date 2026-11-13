package ai.pleos.assistant.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * Presentation-only fixtures. Never clicks pairing, search, approval, or any outbound operation.
 */
final class PairedFinalUiChecks {
  static void run(Instrumentation runner) throws Exception {
    if (!BuildConfig.PAIRED_PHONE_DEMO) throw new AssertionError("paired build required");
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
            require(a, "식당에서 시간 변경을 요청했어요");
            require(a, "19:30");
            require(a, "새 시간 수락");
            require(a, "변경 거절");
            liveChange.put("status", "RECONFIRMING").put("canDecide", false);
            render(a);
            require(a, "식당에 새 시간을 확인 중");
            if (text(a.getWindow().getDecorView()).contains("새 시간 수락"))
              throw new AssertionError("decision remained available");
            a.model.pairedStatus.remove("callback");
            render(a);
            String beforeChange = a.model.pairedStatus.toString();
            var showAlert = a.getClass().getDeclaredMethod("showDemoChangeAlert");
            showAlert.setAccessible(true);
            showAlert.invoke(a);
            var dialogField = a.getClass().getDeclaredField("demoChangeDialog");
            dialogField.setAccessible(true);
            var alert = (android.app.AlertDialog) dialogField.get(a);
            if (alert == null || !alert.isShowing()) throw new AssertionError("alert not shown");
            String alertText = text(alert.getWindow().getDecorView());
            if (!alertText.contains("19:30") || !alertText.contains("시연용 가상 연락"))
              throw new AssertionError("delay or simulation label missing");
            var decide =
                a.getClass()
                    .getDeclaredMethod(
                        "finishDemoChange", String.class, String.class, boolean.class);
            decide.setAccessible(true);
            decide.invoke(a, r.getString("id"), "19:30", true);
            alert.dismiss();
            require(a, "변경 수락을 선택했어요");
            if (!beforeChange.equals(a.model.pairedStatus.toString()))
              throw new AssertionError("simulation changed server result");
            showAlert.invoke(a);
            alert = (android.app.AlertDialog) dialogField.get(a);
            decide.invoke(a, r.getString("id"), "19:10", false);
            alert.dismiss();
            require(a, "기존 시간 19:10 유지를 선택했어요");
            if (!beforeChange.equals(a.model.pairedStatus.toString()))
              throw new AssertionError("simulation changed server result");
            r.put("status", "UNKNOWN");
            alert.dismiss();
            showAlert.invoke(a);
            if (((android.app.AlertDialog) dialogField.get(a)).isShowing())
              throw new AssertionError("UNKNOWN change alert allowed");
            r.put("status", "CONFIRMED");
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
            render(a);
            require(a, "이 조건으로 주변 식당");
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
