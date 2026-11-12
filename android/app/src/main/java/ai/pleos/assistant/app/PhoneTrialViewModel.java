package ai.pleos.assistant.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import java.util.concurrent.Executors;

public final class PhoneTrialViewModel extends AndroidViewModel {
  interface Task {
    void run() throws Exception;
  }

  final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  PhoneTrialRepository repository;
  volatile boolean allowed;
  boolean busy;
  String error = "";
  Runnable observer;

  public PhoneTrialViewModel(Application app) {
    super(app);
    try {
      repository =
          new PhoneTrialRepository(
              new KeystoreVault(app, "phone-trial-v1", "phone-trial.enc", "phone-trial-marker"),
              PhoneTrialRepository.https(() -> allowed));
    } catch (Exception failure) {
      error = "STORAGE_ERROR";
    }
  }

  void run(Task task) {
    if (!allowed || busy || repository == null) return;
    busy = true;
    error = "";
    notifyView();
    worker.execute(
        () -> {
          String result = "";
          try {
            task.run();
          } catch (Exception failure) {
            String code = failure.getMessage();
            result =
                code != null && code.matches("[A-Z][A-Z_0-9]{1,60}") ? code : "CONNECTION_FAILED";
          }
          String code = result;
          main.post(
              () -> {
                busy = false;
                error = code;
                notifyView();
              });
        });
  }

  void notifyView() {
    if (observer != null) observer.run();
  }

  @Override
  protected void onCleared() {
    allowed = false;
    worker.shutdown();
  }
}
