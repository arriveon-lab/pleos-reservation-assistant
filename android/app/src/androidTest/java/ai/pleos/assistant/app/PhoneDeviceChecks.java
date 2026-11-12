package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.assistant.ux.MockPhoneController;
import ai.pleos.assistant.ux.PreviewController;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

final class PhoneDeviceChecks {
  private final Instrumentation runner;
  private ReservationActivity activity;
  private ReservationViewModel model;

  PhoneDeviceChecks(Instrumentation runner) {
    this.runner = runner;
  }

  void run(String fixture) throws Exception {
    check(
        java.util.Set.of(
                "ai.pleos.assistant.mock.integrationvalidation",
                "ai.pleos.assistant.mock.ec2validation",
                "ai.pleos.assistant.mock.ec2navigationvalidation",
                "ai.pleos.assistant.mock.realplacevalidation",
                "ai.pleos.assistant.mock.realplacefiltervalidation")
            .contains(runner.getTargetContext().getPackageName()),
        "isolated installation required");
    activity =
        (ReservationActivity)
            runner.startActivitySync(
                new Intent(runner.getTargetContext(), ReservationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    model = (ReservationViewModel) field(activity, "model");
    if (BuildConfig.REAL_PLACE_DEMO && fixture.equals("PHONE_NAVI_RESTAURANTS")) {
      await(() -> !model.busy());
      var repository = field(model, "repository");
      var auth = field(repository, "transport");
      String phase = ((org.json.JSONObject) field(auth, "state")).optString("phase");
      android.util.Log.i("ArriveOnNaviCheck", "AUTH_PHASE=" + phase);
      check(!"BLOCKED".equals(phase), "existing identity blocked; preserve records");
      await(() -> model.connected() || model.canCreateIdentity());
      var savedRecovery = journal();
      var beforeRecovery =
          savedRecovery == null || savedRecovery.isEmpty()
              ? new org.json.JSONObject()
              : new org.json.JSONObject(savedRecovery);
      var pending = beforeRecovery.optJSONObject("pending");
      if (pending != null) {
        check("/searches".equals(pending.optString("path")), "unexpected pending command");
        click(R.string.reconcile_search);
        await(() -> !model.busy());
        var afterRecovery = new org.json.JSONObject(journal());
        var history = afterRecovery.getJSONArray("searchReconciliations");
        var receipt = history.getJSONObject(history.length() - 1);
        check(
            pending.toString().equals(receipt.getJSONObject("command").toString())
                && !afterRecovery.has("pending"),
            "reconciliation did not preserve original command");
        android.util.Log.i(
            "ArriveOnNaviCheck",
            "ORIGINAL_COMMAND_PRESERVED=true recovery="
                + receipt.getJSONObject("result").getString("state"));
      }
    }
    await(() -> !model.busy() && (model.canCreateIdentity() || model.canWrite()));
    if (main(model::canCreateIdentity)) {
      await(() -> model.restriction().allowsInput());
      click(R.string.auth_create);
    }
    await(model::canWrite);
    String original = journal();
    if (fixture.equals("PHONE_NAVI_QUERY") || fixture.equals("PHONE_NAVI_RESTAURANTS")) {
      String originalPhone =
          runner
              .getTargetContext()
              .getSharedPreferences("mock-phone-recovery", Context.MODE_PRIVATE)
              .getString("journal", "");
      // Preserve the existing confirmed phone journal while inspecting navigation-only UI.
      var phoneWire = (PreviewController.Transport) field(model.phone, "wire");
      model.phone.close();
      main(
          () -> {
            model.phone =
                new MockPhoneController(
                    phoneWire,
                    new MockPhoneController.Store() {
                      public String read() {
                        return null;
                      }

                      public void save(String value) {
                        throw new IllegalStateException("READ_ONLY_CHECK");
                      }
                    },
                    () -> {});
            return null;
          });
      click(R.string.destination_open);
      await(() -> model.navigationState().equals("UNVERIFIED"));
      var received = main(model::navigationDestination);
      check(received != null && received.validFields(), "SDK destination missing");
      var controller = field(model, "destination");
      var wireField = controller.getClass().getDeclaredField("wire");
      wireField.setAccessible(true);
      var originalWire = (PreviewController.Transport) wireField.get(controller);
      var requestMatches = new java.util.concurrent.atomic.AtomicBoolean();
      var response = new java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>();
      wireField.set(
          controller,
          (PreviewController.Transport)
              (path, body, key) -> {
                if (path.equals("/experimental/destination-recommendations") && body != null) {
                  var d = body.getJSONObject("destination");
                  requestMatches.set(
                      received.name().equals(d.getString("name"))
                          && received.latitude() == d.getDouble("latitude")
                          && received.longitude() == d.getDouble("longitude"));
                }
                var answer = originalWire.exchange(path, body, key);
                if (answer.has("result")) response.set(answer);
                return answer;
              });
      click(R.string.navigation_demo_confirm);
      check(
          !main(() -> model.navigationDestination().currentRouteVerified()),
          "demo claimed SDK proof");
      click(R.string.destination_search);
      await(
          () ->
              java.util.Set.of("RESULTS", "EMPTY", "FILTERED", "FAILED", "APPROVAL_REQUIRED")
                  .contains(model.destinationView().state()));
      check(requestMatches.get(), "SDK destination request mismatch");
      var view = main(model::destinationView);
      android.util.Log.i(
          "ArriveOnNaviCheck",
          "SDK_REQUEST_MATCH=" + requestMatches.get() + " terminalUiState=" + view.state());
      capture("actual-navi-query");
      boolean restaurantsOnly = fixture.equals("PHONE_NAVI_RESTAURANTS");
      if (restaurantsOnly) {
        check(
            view.state().equals("RESULTS") && view.candidates().length() > 0,
            "no restaurant results");
      }
      if (view.state().equals("RESULTS")) {
        for (int i = 0; i < view.candidates().length(); i++) {
          var item = view.candidates().getJSONObject(i);
          if (restaurantsOnly) {
            check("RESTAURANT".equals(item.optString("venueCategory")), "non-restaurant candidate");
          }
          String text = main(() -> visibleText(activity.getWindow().getDecorView()));
          check(
              text.contains(item.getString("name")) && text.contains(item.getString("address")),
              "candidate UI mismatch");
        }
      }
      if (response.get() != null) {
        var answer = response.get();
        var result = answer.optJSONObject("result");
        var listing = result == null ? null : result.optJSONObject("listing");
        android.util.Log.i(
            "ArriveOnNaviCheck",
            "serverState="
                + answer.optString("state")
                + " listingState="
                + (listing == null ? "NONE" : listing.optString("state"))
                + " listingFailure="
                + (listing == null ? "NONE" : listing.optString("failure"))
                + " listingExpiryPresent="
                + (listing != null && !listing.isNull("displayValidUntil")));
      }
      check(original.equals(journal()) && !model.phone.hasRequest(), "query changed reservation");
      check(
          originalPhone.equals(
              runner
                  .getTargetContext()
                  .getSharedPreferences("mock-phone-recovery", Context.MODE_PRIVATE)
                  .getString("journal", "")),
          "phone journal changed");
      android.util.Log.i(
          "ArriveOnNaviCheck",
          "SDK_REQUEST_MATCH=true state="
              + view.state()
              + " displayedCandidates="
              + view.candidates().length()
              + " restaurantsOnlyChecked="
              + restaurantsOnly
              + " serverResultReceived="
              + (response.get() != null));
      return;
    }
    if (fixture.equals("PHONE_RESTORE")) {
      check(model.phone.hasRequest(), "saved request missing after restart");
      click(R.string.destination_open);
      click(R.string.phone_refresh);
      await(() -> !model.phone.busy());
      check(model.phone.state().equals("CONFIRMED"), "saved GET did not recover confirmation");
      capture("restored");
      check(original.equals(journal()), "legacy journal changed");
      return;
    }
    if (!fixture.equals("PHONE_FULL")) {
      var wire = (PreviewController.Transport) field(model.phone, "wire");
      main(
          () -> {
            model.phone =
                new MockPhoneController(
                    wire,
                    new MockPhoneController.Store() {
                      String saved;

                      public String read() {
                        return saved;
                      }

                      public void save(String value) {
                        saved = value;
                      }
                    },
                    () ->
                        activity.runOnUiThread(
                            () -> {
                              try {
                                ((DestinationPanel) field(activity, "destinationPanel")).update();
                              } catch (Exception ignored) {
                              }
                            }));
            return null;
          });
    }
    main(
        () -> {
          ((NavigationDestinationAdapter) field(activity, "navigation")).close();
          model.navigation(
              new DestinationController.Destination("가상 성수 시험목적지", 37.544, 127.055, false),
              "UNVERIFIED");
          return null;
        });
    click(R.string.destination_open);
    click(R.string.navigation_demo_confirm);
    click(R.string.destination_search);
    await(() -> model.destinationView().state().equals("RESULTS"));
    check(model.phoneTask() != null, "catalog task unavailable");
    check(model.destinationView().candidates().length() >= 2, "fictional candidates missing");
    capture("candidates");
    if (fixture.equals("PHONE_UNMET") || fixture.equals("PHONE_UNKNOWN")) {
      clickText(
          activity.getString(R.string.phone_scenario, activity.getString(R.string.phone_accept)));
      if (fixture.equals("PHONE_UNKNOWN"))
        clickText(
            activity.getString(R.string.phone_scenario, activity.getString(R.string.phone_unmet)));
    }
    select(1);
    capture("approval");
    clickDialog(R.string.phone_cancel);
    check(!model.phone.hasRequest(), "cancelled approval created request");
    select(1);
    clickDialog(R.string.phone_approve);
    await(() -> !model.phone.busy() && model.phone.hasRequest());
    String expected =
        fixture.equals("PHONE_UNKNOWN")
            ? "UNKNOWN"
            : fixture.equals("PHONE_UNMET") ? "DECLINED" : "CONFIRMED";
    check(expected.equals(model.phone.state()), "unexpected result " + model.phone.state());
    if (fixture.equals("PHONE_UNMET")) {
      check(
          !model.phone.receipt().getBoolean("bookingRequested"),
          "unmet condition requested booking");
      int ordinal = model.phone.receipt().getJSONObject("nextCandidate").getInt("ordinal");
      clickText(
          activity.getString(R.string.phone_scenario, activity.getString(R.string.phone_unmet)));
      clickText(
          activity.getString(R.string.phone_scenario, activity.getString(R.string.phone_unknown)));
      clickText(
          activity.getString(R.string.phone_scenario, activity.getString(R.string.phone_decline)));
      select(ordinal);
      clickDialog(R.string.phone_approve);
      await(() -> !model.phone.busy() && model.phone.state().equals("CONFIRMED"));
    }
    if (fixture.equals("PHONE_UNKNOWN"))
      check(!model.phone.canApprove(model.phoneTask(), 2), "unknown allowed new attempt");
    String attempt = model.phone.receipt().getString("attemptId");
    click(R.string.phone_refresh);
    await(() -> !model.phone.busy());
    check(attempt.equals(model.phone.receipt().getString("attemptId")), "GET changed attempt");
    capture(fixture.toLowerCase() + "-result");
    check(original.equals(journal()), "legacy journal changed");
  }

  private static String visibleText(View view) {
    StringBuilder text = new StringBuilder();
    if (view instanceof TextView label) text.append(label.getText());
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        text.append("\n").append(visibleText(group.getChildAt(i)));
    return text.toString();
  }

  private String journal() {
    return runner
        .getTargetContext()
        .getSharedPreferences("mock-recovery", Context.MODE_PRIVATE)
        .getString("journal", "");
  }

  private void select(int ordinal) throws Exception {
    main(
        () -> {
          View v =
              description(
                  activity.getWindow().getDecorView(),
                  activity.getString(R.string.phone_candidate, ordinal));
          check(v != null && v.isEnabled(), "candidate button disabled");
          v.performClick();
          return null;
        });
    runner.waitForIdleSync();
  }

  private void clickDialog(int id) throws Exception {
    main(
        () -> {
          var panel = (DestinationPanel) field(activity, "destinationPanel");
          var phone = field(panel, "phonePanel");
          android.app.AlertDialog dialog = (android.app.AlertDialog) field(phone, "dialog");
          dialog.getButton(id == R.string.phone_approve ? -1 : -2).performClick();
          return null;
        });
    runner.waitForIdleSync();
  }

  private void click(int id) throws Exception {
    clickText(activity.getString(id));
  }

  private void clickText(String text) throws Exception {
    main(
        () -> {
          View v = find(activity.getWindow().getDecorView(), text);
          check(v != null && v.isEnabled(), "missing button " + text);
          v.performClick();
          return null;
        });
    runner.waitForIdleSync();
  }

  private void capture(String name) throws Exception {
    runner.waitForIdleSync();
    SystemClock.sleep(600); // Wait for dialog dim/exit animation before capturing evidence.
    VehicleUiChecks.capture(runner, "phone56-" + name);
  }

  private <T> T main(Callable<T> action) throws Exception {
    FutureTask<T> t = new FutureTask<>(action);
    runner.runOnMainSync(t);
    return t.get();
  }

  private void await(Callable<Boolean> condition) throws Exception {
    long until = SystemClock.elapsedRealtime() + (BuildConfig.REAL_PLACE_DEMO ? 120000 : 20000);
    while (SystemClock.elapsedRealtime() < until) {
      if (main(condition)) return;
      Thread.sleep(100);
    }
    throw new AssertionError("phone device timeout");
  }

  private static Object field(Object o, String key) throws Exception {
    Field f = o.getClass().getDeclaredField(key);
    f.setAccessible(true);
    return f.get(o);
  }

  private static View find(View v, String text) {
    if (v instanceof TextView t && text.contentEquals(t.getText())) return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View result = find(g.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private static View description(View v, String text) {
    if (text.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
      return v;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View result = description(g.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
