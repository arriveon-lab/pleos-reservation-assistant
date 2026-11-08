package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.MockRepository;
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
  private Runnable observer;
  private JSONObject snapshot = new JSONObject();
  private boolean busy;
  private boolean failed;
  private boolean closed;
  private MockRepository.Restriction restriction = MockRepository.Restriction.UNKNOWN;

  public ReservationViewModel(Application application) {
    super(application);
    SharedPreferences preferences =
        application.getSharedPreferences("mock-recovery", Context.MODE_PRIVATE);
    try {
      repository =
          new MockRepository(
              new LoopbackTransport(),
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
      failed = true;
    }
  }

  void observe(Runnable value) {
    observer = value;
  }

  JSONObject snapshot() {
    return snapshot;
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

  boolean canWrite() {
    return BuildConfig.DEBUG
        && repository != null
        && !busy
        && restriction == MockRepository.Restriction.SIMULATED_PARKED
        && !snapshot.has("pending");
  }

  void restrict(MockRepository.Restriction value) {
    restriction = value;
    if (repository != null) repository.restrict(value);
    if (observer != null) observer.run();
  }

  void run(Action action) {
    if (busy || repository == null || closed) return;
    busy = true;
    if (observer != null) observer.run();
    worker.execute(
        () -> {
          boolean error = false;
          JSONObject next = new JSONObject();
          try {
            action.run(repository);
          } catch (Exception failure) {
            error = true;
          }
          try {
            next = repository.snapshot();
          } catch (Exception failure) {
            error = true;
          }
          final boolean result = error;
          final JSONObject state = next;
          main.post(
              () -> {
                if (closed) return;
                busy = false;
                failed = result;
                snapshot = state;
                if (observer != null) observer.run();
              });
        });
  }

  @Override
  protected void onCleared() {
    closed = true;
    observer = null;
    if (repository != null) repository.restrict(MockRepository.Restriction.UNKNOWN);
    worker.shutdownNow();
  }
}
