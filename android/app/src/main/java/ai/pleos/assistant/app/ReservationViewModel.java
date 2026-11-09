package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
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
  private boolean previewOpen;
  private final Runnable expiry =
      new Runnable() {
        public void run() {
          if (closed || observer == null) return;
          if (preview != null) preview.tick();
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
    if (preview != null) preview.allow(canWrite());
    if (repository != null) repository.restrict(value);
    if (observer != null) observer.run();
  }

  void run(Action action) {
    if (busy || repository == null || closed) return;
    busy = true;
    if (preview != null) preview.allow(false);
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
    if (repository != null) repository.restrict(MockRepository.Restriction.UNKNOWN);
    worker.shutdownNow();
  }
}
