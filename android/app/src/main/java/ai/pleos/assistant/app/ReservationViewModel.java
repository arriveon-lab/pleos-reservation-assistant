package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.assistant.ux.DrivingRestrictionController;
import ai.pleos.assistant.ux.MockRepository;
import ai.pleos.assistant.ux.PreviewController;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class ReservationViewModel extends AndroidViewModel {
  interface Action {
    void run(MockRepository repository) throws Exception;
  }

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private MockRepository repository;
  private PreviewController preview;
  private DestinationController destination;
  ai.pleos.assistant.ux.MockPhoneController phone;
  private AuthSession pairedAuth;
  private SharedPreferences pairedJournal;

  boolean pairedAttempted() {
    return pairedJournal != null && pairedJournal.contains("attempt");
  }

  JSONObject pairedStatus = new JSONObject();
  String pairingCode = "";
  String pairedError = "";
  boolean pairedBusy;
  private boolean pairedRefreshing;
  private long nextPairedPoll;

  private boolean currentPairedResult(JSONObject result) {
    if (!pairedAttempted()) return true;
    try {
      return new JSONObject(pairedJournal.getString("attempt", "{}"))
          .getString("key")
          .equals(result.optString("id"));
    } catch (org.json.JSONException invalid) {
      return false;
    }
  }

  boolean pairedLiveReady() {
    return ai.pleos.assistant.ux.LiveTrialPolicy.ready(pairedStatus, java.time.Instant.now());
  }

  boolean canStartNewPairedDemo() {
    if (!canWrite() || pairedBusy || pairedRefreshing || pairedJournal == null) return false;
    JSONObject latest = pairedStatus.optJSONObject("latest");
    if (latest == null) return !pairedAttempted();
    return !latest.optString("id").isEmpty()
        && currentPairedResult(latest)
        && (ai.pleos.assistant.ux.LiveTrialPolicy.confirmed(latest)
            || ("LIVE".equals(latest.optString("mode"))
                && "DECLINED".equals(latest.optString("status"))));
  }

  boolean startNewPairedDemo() {
    if (!canStartNewPairedDemo()) return false;
    JSONObject latest = pairedStatus.optJSONObject("latest");
    if (latest == null) {
      invalidateDestination("EDITING");
      return true;
    }
    String id = latest.optString("id");
    if (!pairedJournal.edit().putString("completedDemo", id).remove("attempt").commit()) {
      pairedError = "STORAGE_ERROR";
      return false;
    }
    pairedStatus.remove("latest");
    pairedError = "";
    invalidateDestination("EDITING");
    return true;
  }

  private void hideCompletedDemo() {
    JSONObject latest = pairedStatus.optJSONObject("latest");
    if (latest != null
        && pairedJournal != null
        && latest.optString("id").equals(pairedJournal.getString("completedDemo", ""))) {
      pairedStatus.remove("latest");
    }
  }

  private void forgetCompletedMockAttempt(JSONObject latest) {
    if (latest == null
        || !"MOCK".equals(latest.optString("mode"))
        || !java.util.Set.of("CONFIRMED", "DECLINED").contains(latest.optString("status"))
        || !pairedAttempted()
        || !currentPairedResult(latest)) return;
    if (!pairedJournal.edit().remove("attempt").commit()) pairedError = "STORAGE_ERROR";
  }

  String pairedTask() {
    return BuildConfig.PAIRED_PHONE_DEMO && destination != null ? destination.webPhoneTask() : null;
  }

  void pairedRequest(String path, JSONObject body, String key, boolean quiet) {
    if (!BuildConfig.PAIRED_PHONE_DEMO
        || pairedAuth == null
        || closed
        || pairedBusy
        || (quiet && pairedRefreshing)
        || !destinationOpen
        || (body != null && !canWrite())
        || (path.equals("/calls") && body != null && !pairedLiveReady())) return;
    if (quiet) pairedRefreshing = true;
    else {
      pairedBusy = true;
      pairedError = "";
    }
    if (!quiet && observer != null) observer.run();
    worker.execute(
        () -> {
          JSONObject result = null;
          String error = "";
          try {
            if (path.equals("/calls") && body != null && !pairedLiveReady())
              throw new IllegalStateException("LIVE_REQUIRED");
            result = pairedAuth.exchange("/paired-phone" + path, body, key);
            if (path.equals("/disconnect"))
              result = pairedAuth.exchange("/paired-phone", null, null);
          } catch (AuthSession.HttpFailure failure) {
            error = failure.code;
          } catch (Exception failure) {
            error = "UNAVAILABLE";
          }
          JSONObject response = result;
          String code = error;
          main.post(
              () -> {
                if (closed) return;
                if (quiet) pairedRefreshing = false;
                else pairedBusy = false;
                if (response == null) {
                  if (!quiet) pairedError = code.isEmpty() ? "UNAVAILABLE" : code;
                } else if (path.equals("") && body != null) {
                  pairingCode = response.optString("pairingCode");
                } else if (path.equals("/calls")) {
                  try {
                    if (currentPairedResult(response)) pairedStatus.put("latest", response);
                    else pairedError = "RESULT_MISMATCH";
                  } catch (org.json.JSONException impossible) {
                    throw new IllegalStateException(impossible);
                  }
                } else {
                  pairedStatus = response;
                  hideCompletedDemo();
                  JSONObject latest = pairedStatus.optJSONObject("latest");
                  forgetCompletedMockAttempt(latest);
                  if (latest != null && !currentPairedResult(latest)) {
                    pairedStatus.remove("latest");
                    pairedError = "RESULT_MISMATCH";
                  }
                  if (response.optBoolean("active") || path.equals("/disconnect")) pairingCode = "";
                }
                nextPairedPoll = android.os.SystemClock.elapsedRealtime() + 2500;
                if (observer != null) observer.run();
              });
        });
  }

  void approvePairedPhone(String task, int ordinal, String day, String time, String name) {
    if (!canWrite()
        || pairedBusy
        || pairedAttempted()
        || task == null
        || !task.equals(pairedTask())
        || !pairedLiveReady()) return;
    try {
      var body =
          new JSONObject()
              .put("taskId", task)
              .put("candidateOrdinal", ordinal)
              .put("date", day)
              .put("time", time)
              .put("reservationName", name);
      String key = java.util.UUID.randomUUID().toString();
      if (!pairedJournal
          .edit()
          .putString("attempt", new JSONObject().put("key", key).put("body", body).toString())
          .commit()) {
        pairedError = "STORAGE_ERROR";
        if (observer != null) observer.run();
        return;
      }
      pairedRequest("/calls", body, key, false);
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void tickPaired() {
    if (BuildConfig.PAIRED_PHONE_DEMO
        && destinationOpen
        && canWrite()
        && android.os.SystemClock.elapsedRealtime() >= nextPairedPoll) {
      pairedRequest("", null, null, true);
    }
  }

  String phoneTask() {
    return !BuildConfig.REAL_PLACE_DEMO && BuildConfig.MOCK_PHONE_DEMO && destination != null
        ? destination.mockPhoneTask()
        : null;
  }

  void approvePhone(String task, JSONObject body, int party) {
    if (canWrite() && destinationOpen && task.equals(phoneTask())) phone.approve(task, body, party);
  }

  private boolean destinationOpen;
  private DestinationController.Destination navigationDestination;
  private String navigationState = "UNAVAILABLE";

  DestinationController.Destination navigationDestination() {
    return navigationDestination;
  }

  String navigationState() {
    return navigationState;
  }

  boolean navigationReady() {
    return navigationDestination != null && navigationDestination.usable();
  }

  void confirmDemoNavigation() {
    if (!BuildConfig.NAVIGATION_DEMO
        || !canWrite()
        || !destinationOpen
        || navigationDestination == null
        || !navigationDestination.validFields()
        || !navigationState.equals("UNVERIFIED")) return;
    var value = navigationDestination;
    navigationDestination =
        new DestinationController.Destination(
            value.name(), value.latitude(), value.longitude(), false, true);
    navigationState = "DEMO_CONFIRMED";
    invalidateDestination("EDITING");
    if (observer != null) observer.run();
  }

  void navigation(DestinationController.Destination value, String state) {
    navigationDestination = value;
    navigationState = state;
    invalidateDestination("NAVIGATION_CHANGED");
    if (observer != null) observer.run();
  }

  private ai.pleos.assistant.ux.GleoSearchCommand lastVoice;
  private long lastVoiceAt;

  boolean claimVoice(ai.pleos.assistant.ux.GleoSearchCommand command) {
    if (!canWrite()
        || destination == null
        || java.util.Set.of("LOADING", "QUEUED", "RUNNING").contains(destination.view().state()))
      return false;
    long now = android.os.SystemClock.elapsedRealtime();
    if (command.equals(lastVoice) && now - lastVoiceAt < 15000) return false;
    lastVoice = command;
    lastVoiceAt = now;
    return true;
  }

  private boolean previewOpen;
  private final Runnable expiry =
      new Runnable() {
        public void run() {
          if (closed || observer == null) return;
          if (preview != null) preview.tick();
          if (destination != null) destination.tick();
          tickPaired();
          main.postDelayed(this, 1000);
        }
      };
  private Runnable observer;
  private JSONObject snapshot = new JSONObject();
  private boolean busy;
  private boolean connected;
  private boolean canCreateIdentity;
  private String failureCode = "";
  private boolean failed;
  private boolean closed;
  private DrivingRestrictionController.State driving =
      new DrivingRestrictionController.State(
          MockRepository.Restriction.UNKNOWN,
          DrivingRestrictionController.Reason.STOPPED,
          false,
          -1);
  private volatile MockRepository.Restriction restriction = MockRepository.Restriction.UNKNOWN;

  public ReservationViewModel(Application application) {
    super(application);
    SharedPreferences preferences =
        application.getSharedPreferences("mock-recovery", Context.MODE_PRIVATE);
    try {
      AuthSession auth =
          new AuthSession(
              new LoopbackTransport(() -> restriction.allowsInput()),
              new KeystoreVault(application));
      if (!auth.canCreate() && preferences.getString("journal", null) == null)
        throw new IllegalStateException("RECOVERY_STORAGE_MISSING");
      if (BuildConfig.PAIRED_PHONE_DEMO) {
        pairedAuth = auth;
        pairedJournal =
            application.getSharedPreferences("paired-call-recovery", Context.MODE_PRIVATE);
      }
      preview =
          new PreviewController(
              auth::exchangeTransient,
              () ->
                  main.post(
                      () -> {
                        if (!closed && observer != null) observer.run();
                      }),
              java.time.Clock.systemUTC(),
              android.os.SystemClock::elapsedRealtimeNanos);
      destination =
          new DestinationController(
              auth::exchangeTransient,
              () ->
                  main.post(
                      () -> {
                        if (!closed && observer != null) observer.run();
                      }),
              java.time.Clock.systemUTC(),
              android.os.SystemClock::elapsedRealtimeNanos);
      var phonePreferences =
          application.getSharedPreferences("mock-phone-recovery", Context.MODE_PRIVATE);
      phone =
          new ai.pleos.assistant.ux.MockPhoneController(
              auth::exchangeTransient,
              new ai.pleos.assistant.ux.MockPhoneController.Store() {
                public String read() {
                  return phonePreferences.getString("journal", null);
                }

                public void save(String value) throws Exception {
                  if (!phonePreferences.edit().putString("journal", value).commit())
                    throw new IllegalStateException("PHONE_STORAGE_FAILED");
                }
              },
              () ->
                  main.post(
                      () -> {
                        if (!closed && observer != null) observer.run();
                      }));
      repository =
          new MockRepository(
              auth,
              new MockRepository.Journal() {
                public String read() {
                  return preferences.getString("journal", null);
                }

                public void save(String value) {
                  if (!preferences.edit().putString("journal", value).commit())
                    throw new IllegalStateException("RECOVERY_WRITE_FAILED");
                }
              });
    } catch (Exception error) {
      failureCode = "RECOVERY_REQUIRED";
      failed = true;
    }
  }

  void observe(Runnable value) {
    observer = value;
    main.removeCallbacks(expiry);
    if (value != null) main.post(expiry);
  }

  boolean previewOpen() {
    return previewOpen;
  }

  PreviewController.View previewView() {
    return preview.view();
  }

  void openPreview() {
    if (!canWrite() || preview == null) return;
    previewOpen = true;
    preview.allow(true);
    if (observer != null) observer.run();
  }

  void closePreview() {
    previewOpen = false;
    if (preview != null) preview.invalidate("CLOSED");
    if (observer != null) observer.run();
  }

  void invalidatePreview(String reason) {
    if (preview != null) preview.invalidate(reason);
  }

  void searchPreview(PreviewController.Input input) {
    if (canWrite() && previewOpen) preview.search(input);
  }

  boolean destinationOpen() {
    return destinationOpen;
  }

  DestinationController.View destinationView() {
    return destination.view();
  }

  boolean currentNavigationCandidate(int ordinal, String name, String address) {
    if (!BuildConfig.REAL_PLACE_DEMO || !canWrite() || !destinationOpen) return false;
    destination.tick();
    var view = destination.view();
    if (!"RESULTS".equals(view.state())) return false;
    for (int i = 0; i < view.candidates().length(); i++) {
      var item = view.candidates().optJSONObject(i);
      if (item != null
          && item.optInt("ordinal") == ordinal
          && name.equals(item.optString("name"))
          && address.equals(item.optString("address"))
          && "RESTAURANT".equals(item.optString("venueCategory"))) return true;
    }
    return false;
  }

  void openDestination() {
    if (!canWrite() || destination == null) return;
    destinationOpen = true;
    destination.allow(true);
    tickPaired();
    if (observer != null) observer.run();
  }

  private void clearDemoConfirmation() {
    if (navigationDestination == null || !navigationDestination.demoConfirmed()) return;
    var value = navigationDestination;
    navigationDestination =
        new DestinationController.Destination(
            value.name(), value.latitude(), value.longitude(), false);
    navigationState = "UNVERIFIED";
  }

  void closeDestination() {
    clearDemoConfirmation();
    destinationOpen = false;
    invalidateDestination("CLOSED");
    if (observer != null) observer.run();
  }

  void invalidateDestination(String reason) {
    if (destination != null) destination.invalidate(reason);
  }

  void searchDestination(DestinationController.Input input) {
    if (canWrite()
        && destinationOpen
        && (!BuildConfig.PAIRED_PHONE_DEMO || pairedLiveReady())
        && navigationReady()
        && navigationDestination.equals(input.destination())) destination.search(input);
    else invalidateDestination("NAVIGATION_REQUIRED");
  }

  JSONObject snapshot() {
    return snapshot;
  }

  boolean canCreateIdentity() {
    return canCreateIdentity;
  }

  String failureCode() {
    return failureCode;
  }

  boolean busy() {
    return busy;
  }

  boolean failed() {
    return failed;
  }

  MockRepository.Restriction restriction() {
    return restriction;
  }

  boolean connected() {
    return connected;
  }

  boolean canWrite() {
    return BuildConfig.DEBUG
        && connected
        && repository != null
        && !busy
        && restriction.allowsInput()
        && !snapshot.has("pending");
  }

  DrivingRestrictionController.State driving() {
    return driving;
  }

  void driving(DrivingRestrictionController.State value) {
    driving = value;
    restrict(value.restriction());
  }

  private void restrict(MockRepository.Restriction value) {
    restriction = value;
    if (phone != null) phone.allow(canWrite());
    if (!value.allowsInput()) clearDemoConfirmation();
    if (preview != null) preview.allow(canWrite());
    if (destination != null) destination.allow(canWrite());
    if (repository != null) repository.restrict(value);
    if (observer != null) observer.run();
  }

  void run(Action action) {
    if (busy || repository == null || closed) return;
    busy = true;
    if (phone != null) phone.allow(false);
    if (preview != null) preview.allow(false);
    if (destination != null) destination.allow(false);
    if (observer != null) observer.run();
    worker.execute(
        () -> {
          boolean error = false;
          String code = "";
          JSONObject next = new JSONObject();
          try {
            action.run(repository);
          } catch (Exception failure) {
            code =
                failure instanceof IllegalStateException
                        && "AUTH_CREATE_REQUIRED".equals(failure.getMessage())
                    ? "AUTH_CREATE_REQUIRED"
                    : "RECOVERY_REQUIRED";
            error = true;
          }
          try {
            next = repository.snapshot();
          } catch (Exception failure) {
            error = true;
          }
          final String reportedCode = code;
          final boolean canCreate = repository.canCreateIdentity();
          final boolean connection = repository.isVerified() && !error;
          final boolean result = error;
          final JSONObject state = next;
          main.post(
              () -> {
                if (closed) return;
                busy = false;
                failed = result;
                failureCode =
                    "AUTH_CREATE_REQUIRED".equals(reportedCode) && !canCreate
                        ? "RECOVERY_REQUIRED"
                        : reportedCode;
                canCreateIdentity = canCreate;
                connected = connection;
                snapshot = state;
                if (preview != null) preview.allow(canWrite());
                if (destination != null) destination.allow(canWrite());
                if (observer != null) observer.run();
              });
        });
  }

  @Override
  protected void onCleared() {
    closed = true;
    observer = null;
    main.removeCallbacks(expiry);
    if (preview != null) preview.close();
    if (destination != null) destination.close();
    if (phone != null) phone.close();
    if (repository != null) repository.restrict(MockRepository.Restriction.UNKNOWN);
    worker.shutdownNow();
  }
}
