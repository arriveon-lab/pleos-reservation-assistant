package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DrivingRestrictionController.Observer;
import ai.pleos.assistant.ux.DrivingRestrictionController.Reason;
import ai.pleos.assistant.ux.DrivingRestrictionController.Source;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** SDK 35 public AAOS API. No vehicle-speed inference, hidden API or fabricated parked fallback. */
final class CarRestrictionSource {
  private CarRestrictionSource() {}

  static Source create(Context context) {
    if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
      return unavailable(Reason.UNSUPPORTED);
    try {
      Class.forName("android.car.Car", false, context.getClassLoader());
      return new AaosSource(context);
    } catch (ClassNotFoundException | LinkageError unavailable) {
      return unavailable(Reason.MISSING_LIBRARY);
    }
  }

  private static Source unavailable(Reason reason) {
    return new Source() {
      public void open(Observer observer) {
        observer.failure(reason);
      }

      public void close() {}
    };
  }

  private static final class AaosSource implements Source {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Car car;
    private CarUxRestrictionsManager manager;
    private long generation;
    private boolean running;
    private Runnable timeout, health;

    AaosSource(Context context) {
      this.context = context;
    }

    public void open(Observer observer) {
      long ticket = ++generation;
      running = true;
      timeout =
          () -> {
            if (valid(ticket)) observer.failure(Reason.CONNECT_TIMEOUT);
          };
      main.postDelayed(timeout, 10000);
      car =
          Car.createCar(
              context,
              main,
              Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
              (connected, ready) -> main.post(() -> connected(ticket, connected, ready, observer)));
      if (car == null) observer.failure(Reason.SERVICE_DISCONNECTED);
    }

    private boolean valid(long ticket) {
      return running && generation == ticket;
    }

    private void connected(long ticket, Car connected, boolean ready, Observer observer) {
      if (!valid(ticket)) return;
      car = connected;
      if (!ready) {
        observer.failure(Reason.SERVICE_DISCONNECTED);
        return;
      }
      try {
        Object service = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
        if (!(service instanceof CarUxRestrictionsManager found)) {
          observer.failure(Reason.UNSUPPORTED);
          return;
        }
        manager = found;
        manager.registerListener(value -> main.post(() -> read(ticket, observer, "CALLBACK")));
        read(ticket, observer, "INITIAL");
        health =
            new Runnable() {
              public void run() {
                if (!valid(ticket)) return;
                read(ticket, observer, "HEALTH");
                if (valid(ticket)) main.postDelayed(this, 5000);
              }
            };
        if (valid(ticket)) main.postDelayed(health, 5000);
      } catch (SecurityException denied) {
        observer.failure(Reason.PERMISSION_DENIED);
      } catch (RuntimeException | LinkageError failure) {
        observer.failure(Reason.READ_FAILED);
      }
    }

    private void read(long ticket, Observer observer, String event) {
      if (!valid(ticket)) return;
      try {
        if (car == null || !car.isConnected() || manager == null) {
          observer.failure(Reason.SERVICE_DISCONNECTED);
          return;
        }
        // Re-read rather than applying a queued, potentially older callback payload.
        CarUxRestrictions current = manager.getCurrentCarUxRestrictions();
        if (current == null) {
          observer.failure(Reason.READ_FAILED);
          return;
        }
        main.removeCallbacks(timeout);
        if (!"HEALTH".equals(event))
          Log.i(
              "PleosUx",
              "event="
                  + event
                  + " requiresDo="
                  + current.isRequiresDistractionOptimization()
                  + " flags="
                  + current.getActiveRestrictions());
        observer.reading(
            current.isRequiresDistractionOptimization(), current.getActiveRestrictions());
      } catch (SecurityException denied) {
        observer.failure(Reason.PERMISSION_DENIED);
      } catch (RuntimeException | LinkageError failure) {
        observer.failure(Reason.READ_FAILED);
      }
    }

    public void close() {
      running = false;
      ++generation;
      if (timeout != null) main.removeCallbacks(timeout);
      if (health != null) main.removeCallbacks(health);
      CarUxRestrictionsManager previous = manager;
      Car connection = car;
      manager = null;
      car = null;
      try {
        if (previous != null) previous.unregisterListener();
      } finally {
        if (connection != null) connection.disconnect();
      }
      if (previous != null || connection != null) Log.i("PleosUx", "event=DETACHED");
    }
  }
}
