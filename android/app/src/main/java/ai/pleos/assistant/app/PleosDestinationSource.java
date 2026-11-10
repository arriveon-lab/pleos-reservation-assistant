package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationRecommendationState;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.TripInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.time.Instant;
import java.util.Objects;
import kotlin.Unit;

/**
 * Product-side destination observation only; raw TripInfo is never converted to an arrival time.
 */
final class PleosDestinationSource implements AutoCloseable {
  enum Status {
    NOT_REQUESTED,
    INITIALIZING,
    QUERYING,
    AVAILABLE_UNVERIFIED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    CLEARED
  }

  private final Context context;
  private final DestinationRecommendationState state;
  private final Runnable changed;
  private final Handler main = new Handler(Looper.getMainLooper());
  private NaviHelper helper;
  private boolean listenerRegistered;
  private boolean closed;
  private Status status = Status.NOT_REQUESTED;

  private final NaviHelperEventListener listener =
      new NaviHelperEventListener() {
        @Override
        public void onDestinationInfo(DestinationInfo info) {
          Instant observedAt = Instant.now();
          main.post(() -> accept(info, observedAt));
        }

        @Override
        public void onDestinationChanged() {
          main.post(() -> clear(true));
        }

        @Override
        public void onDestinationArrived(DestinationArrivedInfo info) {
          main.post(() -> clear(false));
        }

        @Override
        public void onRouteCancelled() {
          main.post(() -> clear(false));
        }

        @Override
        public void onRouteStarted(RouteStartInfo info) {
          main.post(() -> clear(false));
        }

        @Override
        public void onRoutingCompleted() {
          main.post(() -> clear(true));
        }

        @Override
        public void onReRouteEnded() {
          main.post(() -> clear(true));
        }

        @Override
        public void onTripInfo(TripInfo info) {
          // The SDK's ETA time basis and response association are unverified.
        }

        @Override
        public void onError(NaviErrorCode error) {
          main.post(() -> fail(Status.UNAVAILABLE));
        }
      };

  PleosDestinationSource(Context context, DestinationRecommendationState state, Runnable changed) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.state = Objects.requireNonNull(state);
    this.changed = Objects.requireNonNull(changed);
  }

  void start() {
    if (closed || helper != null) return;
    if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE")
        != PackageManager.PERMISSION_GRANTED) {
      fail(Status.PERMISSION_DENIED);
      return;
    }
    status = Status.INITIALIZING;
    changed.run();
    try {
      NaviHelper pending = new NaviHelper(context);
      helper = pending;
      pending.initialize(
          () -> {
            main.post(
                () -> {
                  if (closed || helper != pending) return;
                  try {
                    pending.addListener(listener);
                    listenerRegistered = true;
                    requestDestination();
                  } catch (RuntimeException failure) {
                    fail(Status.UNAVAILABLE);
                  }
                });
            return Unit.INSTANCE;
          },
          error -> {
            main.post(() -> fail(Status.UNAVAILABLE));
            return Unit.INSTANCE;
          });
    } catch (RuntimeException failure) {
      fail(Status.UNAVAILABLE);
    }
  }

  Status status() {
    return status;
  }

  private void requestDestination() {
    if (closed || helper == null) return;
    state.invalidateRoute();
    status = Status.QUERYING;
    changed.run();
    try {
      helper.getDestinationInfo();
    } catch (RuntimeException failure) {
      fail(Status.UNAVAILABLE);
    }
  }

  private void accept(DestinationInfo info, Instant observedAt) {
    if (closed || helper == null || status != Status.QUERYING) return;
    if (info == null
        || !Double.isFinite(info.getLatitude())
        || !Double.isFinite(info.getLongitude())
        || info.getLatitude() < -90
        || info.getLatitude() > 90
        || info.getLongitude() < -180
        || info.getLongitude() > 180
        || info.getName() == null
        || info.getName().isBlank()
        || info.getPoiId() == null) {
      state.invalidateRoute();
      status = Status.UNAVAILABLE;
    } else {
      state.observeDestination(
          new DestinationRecommendationState.Destination(
              info.getLatitude(),
              info.getLongitude(),
              info.getName(),
              info.getPoiId(),
              observedAt));
      // NaviHelper has no response correlation ID; display is provisional.
      status = Status.AVAILABLE_UNVERIFIED;
    }
    changed.run();
  }

  private void clear(boolean refresh) {
    if (closed) return;
    state.invalidateRoute();
    status = Status.CLEARED;
    changed.run();
    if (refresh) requestDestination();
  }

  private void fail(Status failure) {
    if (closed) return;
    state.invalidateRoute();
    status = failure;
    release();
    changed.run();
  }

  private void release() {
    NaviHelper previous = helper;
    helper = null;
    if (previous == null) return;
    try {
      if (listenerRegistered) previous.removeListener(listener);
    } catch (RuntimeException ignored) {
      // The source is already invalidated; never reattach on a failed removal.
    } finally {
      listenerRegistered = false;
      try {
        previous.release();
      } catch (RuntimeException ignored) {
        // No SDK state is used after release was attempted.
      }
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    state.invalidateRoute();
    status = Status.CLEARED;
    release();
    main.removeCallbacksAndMessages(null);
    changed.run();
  }
}
