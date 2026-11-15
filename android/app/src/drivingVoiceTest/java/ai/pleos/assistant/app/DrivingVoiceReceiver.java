package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.assistant.ux.DrivingRestrictionController;
import ai.pleos.assistant.ux.GleoSearchCommand;
import ai.pleos.assistant.ux.MockPhoneController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Headless Gleo actions included only in the isolated, fictional-reservation APK. */
public final class DrivingVoiceReceiver extends BroadcastReceiver {
  static final String PREFERENCES = "driving-voice-mock";
  static final String JOURNAL = "journal";
  private static final String FIND = "FIND_TEST_RESTAURANTS";
  private static final String SELECT = "SELECT_TEST_RESTAURANT";
  private static final String APPROVE = "APPROVE_TEST_RESERVATION";
  private static final String TAG = "ArriveOnDriveVoice";
  private static Session session;

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (action == null) return;
    String prefix = context.getPackageName() + ".";
    String function = action.startsWith(prefix) ? action.substring(prefix.length()) : action;
    if (!FIND.equals(function) && !SELECT.equals(function) && !APPROVE.equals(function)) return;
    JSONObject parameters;
    try {
      parameters = parameters(intent);
    } catch (Exception invalid) {
      respond(context, function, false, "The voice command parameters are invalid.");
      return;
    }
    if (session == null) session = new Session(context.getApplicationContext());
    session.accept(function, parameters, goAsync());
  }

  private static JSONObject parameters(Intent intent) throws Exception {
    Bundle extras = intent.getExtras();
    if (extras == null) return new JSONObject();
    Object raw = extras.get("ACTION_PARAM");
    if (raw == null) {
      JSONObject values = new JSONObject();
      for (String key : new String[] {"adults", "children", "candidateOrdinal"}) {
        if (extras.containsKey(key)) values.put(key, extras.get(key));
      }
      return values;
    }
    if (!(raw instanceof String text) || text.length() > 1024) throw new IllegalArgumentException();
    if (extras.containsKey("adults")
        || extras.containsKey("children")
        || extras.containsKey("candidateOrdinal")) throw new IllegalArgumentException();
    return new JSONObject(text);
  }

  private static void respond(Context context, String function, boolean success, String message) {
    try {
      String status = success ? "success" : "fail";
      JSONObject response =
          new JSONObject()
              .put("functionName", function)
              .put("status", status)
              .put("mode", "voice")
              .put("message", message);
      context.sendBroadcast(
          new Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
              .setPackage("ai.umos.ambientai")
              .putExtra("packageName", context.getPackageName())
              .putExtra("result", status)
              .putExtra("response", response.toString()));
      Log.i(TAG, function + " " + status);
    } catch (Exception failure) {
      Log.w(TAG, "CALLBACK_FAILED");
    }
  }

  private record Request(
      String function, JSONObject parameters, PendingResult result, long until) {}

  private record Selection(String task, int ordinal, String name, Instant time) {}

  private static final class Session {
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DrivingRestrictionController driving;
    private final LoopbackTransport transport;
    private final DestinationController destinations;
    private final MockPhoneController phone;
    private final Runnable step = this::advance;
    private final Runnable idle = this::close;
    private volatile boolean platformReady;
    private AuthSession auth;
    private Request request;
    private Selection selection;
    private GleoSearchCommand party;
    private boolean started;

    Session(Context context) {
      this.context = context;
      transport = new LoopbackTransport(() -> platformReady);
      destinations =
          new DestinationController(
              this::exchange, this::schedule, Clock.systemUTC(), SystemClock::elapsedRealtimeNanos);
      var preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
      phone =
          new MockPhoneController(
              this::exchange,
              new MockPhoneController.Store() {
                public String read() {
                  return preferences.getString(JOURNAL, null);
                }

                public void save(String value) {
                  if (!preferences.edit().putString(JOURNAL, value).commit())
                    throw new IllegalStateException("VOICE_STORAGE_FAILED");
                }
              },
              this::schedule);
      driving =
          new DrivingRestrictionController(CarRestrictionSource.create(context), this::vehicle);
      driving.start();
    }

    private synchronized AuthSession identity() throws Exception {
      if (auth == null)
        auth =
            new AuthSession(
                transport,
                new KeystoreVault(
                    context, "driving-voice-auth", "driving-voice.enc", "driving-voice-marker"));
      if (auth.canCreate()) auth.createIdentity();
      return auth;
    }

    private JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (body != null && !path.endsWith("/close") && !platformReady)
        throw new IllegalStateException("VEHICLE_STATE_UNAVAILABLE");
      return identity().exchangeTransient(path, body, key);
    }

    private void vehicle(DrivingRestrictionController.State value) {
      platformReady = value.reason() == DrivingRestrictionController.Reason.PLATFORM;
      destinations.allow(platformReady);
      phone.allow(platformReady);
      if (!platformReady) selection = null;
      Log.i(TAG, "VEHICLE " + value.reason() + " flags=" + value.flags());
      schedule();
    }

    void accept(String function, JSONObject parameters, PendingResult result) {
      if (request != null) {
        respond(context, function, false, "The previous voice command is still running.");
        result.finish();
        return;
      }
      main.removeCallbacks(idle);
      request = new Request(function, parameters, result, SystemClock.elapsedRealtime() + 6000);
      started = false;
      advance();
    }

    private void schedule() {
      main.removeCallbacks(step);
      main.post(step);
    }

    private void advance() {
      if (request == null) return;
      if (SystemClock.elapsedRealtime() >= request.until()) {
        if (FIND.equals(request.function())) destinations.invalidate("VOICE_TIMEOUT");
        finish(false, "The result is unavailable. No reservation is confirmed by this response.");
        return;
      }
      if (!platformReady) {
        main.postDelayed(step, 250);
        return;
      }
      try {
        switch (request.function()) {
          case FIND -> search();
          case SELECT -> select();
          case APPROVE -> approve();
          default -> throw new IllegalArgumentException();
        }
      } catch (Exception invalid) {
        finish(false, "The requested option or voice command is invalid.");
      }
    }

    private void search() throws Exception {
      if (!started) {
        GleoSearchCommand counts = GleoSearchCommand.parse(request.parameters());
        if (counts.adults() > 8 || counts.children() > 6) throw new IllegalArgumentException();
        if (phone.hasRequest() && !phone.startNew()) {
          finish(false, "The previous simulated reservation is still unresolved.");
          return;
        }
        selection = null;
        party = counts;
        started = true;
        destinations.search(
            new DestinationController.Input(
                new DestinationController.Destination(
                    context.getString(R.string.phone_mock_destination_name),
                    37.544,
                    127.055,
                    false,
                    true),
                party.adults(),
                party.children(),
                false,
                false));
      }
      var view = destinations.view();
      switch (view.state()) {
        case "RESULTS" -> finish(true, "These are fictional options. " + names(view.candidates()));
        case "LOADING", "QUEUED", "RUNNING" -> {
          destinations.tick();
          main.postDelayed(step, 350);
        }
        default -> finish(false, "No simulated restaurant options are available.");
      }
    }

    private static String names(JSONArray candidates) throws Exception {
      StringBuilder result = new StringBuilder();
      for (int index = 0; index < candidates.length(); index++) {
        JSONObject candidate = candidates.getJSONObject(index);
        result
            .append("Option ")
            .append(candidate.getInt("ordinal"))
            .append(" is ")
            .append(candidate.getString("name"))
            .append(". ");
      }
      return result.append("Ask the user to select one option by number.").toString();
    }

    private int ordinal() throws Exception {
      Object value = request.parameters().get("candidateOrdinal");
      if (!(value instanceof Number number)) throw new IllegalArgumentException();
      int ordinal = number.intValue();
      if (ordinal < 1 || ordinal > 3 || number.doubleValue() != ordinal)
        throw new IllegalArgumentException();
      return ordinal;
    }

    private void select() throws Exception {
      selection = null;
      int ordinal = ordinal();
      String task = destinations.mockPhoneTask();
      if (phone.hasRequest() || task == null) {
        finish(false, "Find current fictional options before selecting a restaurant.");
        return;
      }
      JSONArray candidates = destinations.view().candidates();
      for (int index = 0; index < candidates.length(); index++) {
        JSONObject candidate = candidates.getJSONObject(index);
        if (candidate.getInt("ordinal") != ordinal) continue;
        String name = candidate.getString("name");
        selection =
            new Selection(
                task,
                ordinal,
                name,
                Instant.now().plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES));
        finish(
            true,
            "Option "
                + ordinal
                + " is "
                + name
                + " for "
                + (party.adults() + party.children())
                + " people at "
                + TIME.format(selection.time())
                + " Korea time. Ask the user to explicitly approve this simulated reservation by option number.");
        return;
      }
      finish(false, "There is no restaurant with that option number in the current results.");
    }

    private void approve() throws Exception {
      if (!started) {
        String task = destinations.mockPhoneTask();
        if (selection == null
            || ordinal() != selection.ordinal()
            || !selection.task().equals(task)
            || !selection.time().isAfter(Instant.now())
            || !phone.canApprove(task, selection.ordinal())) {
          finish(false, "Select the current option and verify its details before approval.");
          return;
        }
        JSONObject body =
            new JSONObject()
                .put("approved", true)
                .put("candidateOrdinal", selection.ordinal())
                .put("reservationAt", selection.time().toString())
                .put("reservationName", context.getString(R.string.design_test_name))
                .put("conditionScenario", "MET")
                .put("availabilityScenario", "AVAILABLE")
                .put("bookingScenario", "ACCEPT");
        started = true;
        phone.approve(task, body, party.adults() + party.children());
      }
      if (phone.busy()) return;
      Selection approved = selection;
      selection = null;
      if (approved != null && "CONFIRMED".equals(phone.state())) {
        Log.i(
            TAG, "MOCK_RECEIPT_CONFIRMED externalCalls=" + phone.receipt().getInt("externalCalls"));
        finish(
            true,
            "The simulated reservation at "
                + approved.name()
                + " for "
                + TIME.format(approved.time())
                + " Korea time is confirmed. No restaurant was called.");
      } else {
        finish(false, "The simulated reservation could not be confirmed.");
      }
    }

    private void finish(boolean success, String message) {
      Request completed = request;
      request = null;
      main.removeCallbacks(step);
      try {
        respond(context, completed.function(), success, message);
      } finally {
        completed.result().finish();
        main.postDelayed(idle, 300000);
      }
    }

    private void close() {
      driving.stop();
      destinations.close();
      phone.close();
      session = null;
    }
  }
}
