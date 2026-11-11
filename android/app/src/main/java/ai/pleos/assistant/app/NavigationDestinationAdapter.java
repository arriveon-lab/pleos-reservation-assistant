package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.function.BiConsumer;
import kotlin.Unit;

/** Read-only NaviHelper3.0.5 adapter, based on #47 bbfb184. Never requests ETA or rerouting. */
final class NavigationDestinationAdapter implements AutoCloseable {
  private final Context context;
  private final BiConsumer<DestinationController.Destination, String> changed;
  private final Handler main = new Handler(Looper.getMainLooper());
  private NaviHelper helper;
  private boolean closed, listening, awaiting;
  private final NaviHelperEventListener listener =
      new NaviHelperEventListener() {
        @Override
        public void onDestinationInfo(DestinationInfo info) {
          post(
              () -> {
                if (!awaiting) return;
                awaiting = false;
                DestinationController.Destination value =
                    info == null
                        ? null
                        : new DestinationController.Destination(
                            info.getName(), info.getLatitude(), info.getLongitude(), false);
                // SDK callback has no request/route identifier. Never turn valid fields into
                // CURRENT.
                changed.accept(
                    value != null && value.validFields() ? value : null,
                    value != null && value.validFields() ? "UNVERIFIED" : "UNAVAILABLE");
                android.util.Log.i(
                    "ArriveOnNavi",
                    value != null && value.validFields()
                        ? "DESTINATION_UNVERIFIED"
                        : "DESTINATION_UNAVAILABLE");
              });
        }

        @Override
        public void onDestinationChanged() {
          post(() -> invalidate(true));
        }

        @Override
        public void onRoutingCompleted() {
          post(() -> invalidate(true));
        }

        @Override
        public void onReRouteEnded() {
          post(() -> invalidate(true));
        }

        @Override
        public void onRouteStarted(RouteStartInfo info) {
          post(() -> invalidate(false));
        }

        @Override
        public void onRouteCancelled() {
          post(() -> invalidate(false));
        }

        @Override
        public void onDestinationArrived(DestinationArrivedInfo info) {
          post(() -> invalidate(false));
        }

        @Override
        public void onError(NaviErrorCode error) {
          post(() -> invalidate(false));
        }
      };

  NavigationDestinationAdapter(
      Context context, BiConsumer<DestinationController.Destination, String> changed) {
    this.context = context.getApplicationContext();
    this.changed = changed;
  }

  void start() {
    changed.accept(null, "CONNECTING");
    try {
      helper = new NaviHelper(context);
      helper.initialize(
          () -> {
            post(
                () -> {
                  try {
                    helper.addListener(listener);
                    listening = true;
                    invalidate(true);
                  } catch (RuntimeException failure) {
                    fail();
                  }
                });
            return Unit.INSTANCE;
          },
          error -> {
            post(this::fail);
            return Unit.INSTANCE;
          });
    } catch (RuntimeException failure) {
      fail();
    }
  }

  private void post(Runnable event) {
    main.post(
        () -> {
          if (!closed) event.run();
        });
  }

  private void invalidate(boolean query) {
    awaiting = false;
    changed.accept(null, "UNAVAILABLE");
    if (query && helper != null) {
      awaiting = true;
      try {
        helper.getDestinationInfo();
      } catch (RuntimeException failure) {
        fail();
      }
    }
  }

  private void fail() {
    changed.accept(null, "UNAVAILABLE");
    close();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    awaiting = false;
    changed.accept(null, "UNAVAILABLE");
    main.removeCallbacksAndMessages(null);
    NaviHelper previous = helper;
    helper = null;
    if (previous == null) return;
    try {
      if (listening) previous.removeListener(listener);
    } catch (RuntimeException ignored) {
      /* Remain unavailable. */
    } finally {
      try {
        previous.release();
      } catch (RuntimeException ignored) {
        /* Never keep stale navigation on release failure. */
      }
    }
  }
}
