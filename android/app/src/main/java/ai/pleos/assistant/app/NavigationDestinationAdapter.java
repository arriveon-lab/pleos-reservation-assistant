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

/** NaviHelper3.0.5 destination reads and explicit native place search; no ETA conversion. */
final class NavigationDestinationAdapter implements AutoCloseable {
  private final Context context;
  private final BiConsumer<DestinationController.Destination, String> changed;
  private final Handler main = new Handler(Looper.getMainLooper());
  // UI response wait limit, not a navigation freshness/TTL guarantee.
  private static final long RESPONSE_WAIT_MS = 10_000;
  private final Runnable responseTimeout = () -> fail("TIMEOUT");
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
                main.removeCallbacks(responseTimeout);
                DestinationController.Destination value =
                    info == null
                        ? null
                        : new DestinationController.Destination(
                            info.getName(), info.getLatitude(), info.getLongitude(), false);
                // SDK callback has no request/route identifier. Never turn valid fields into
                // CURRENT.
                changed.accept(
                    value != null && value.validFields() ? value : null,
                    value != null && value.validFields() ? "UNVERIFIED" : "MISSING_OR_INVALID");
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
    if (closed || helper != null) return;
    changed.accept(null, "CONNECTING");
    main.postDelayed(responseTimeout, RESPONSE_WAIT_MS);
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
                    fail(failure);
                  }
                });
            return Unit.INSTANCE;
          },
          error -> {
            post(() -> fail(error));
            return Unit.INSTANCE;
          });
    } catch (RuntimeException failure) {
      fail(failure);
    }
  }

  boolean searchPlace(String name) {
    if (closed || helper == null || !listening || name == null || name.isBlank()) return false;
    if (context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP")
        != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
    try {
      helper.requestNavigationScreen(
          new ai.pleos.playground.navi.data.NavigationScreenRequest.SearchPlace(name));
      android.util.Log.i("ArriveOnNavi", "PLACE_SEARCH_REQUESTED");
      return true;
    } catch (RuntimeException failure) {
      android.util.Log.i("ArriveOnNavi", "PLACE_SEARCH_FAILED");
      return false;
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
    main.removeCallbacks(responseTimeout);
    changed.accept(null, query ? "READING" : "UNAVAILABLE");
    if (query && helper != null) {
      awaiting = true;
      main.postDelayed(responseTimeout, RESPONSE_WAIT_MS);
      try {
        helper.getDestinationInfo();
      } catch (RuntimeException failure) {
        fail(failure);
      }
    }
  }

  private void fail(Throwable failure) {
    fail(failure instanceof SecurityException ? "PERMISSION_DENIED" : "UNAVAILABLE");
  }

  private void fail(String state) {
    if (closed) return;
    close();
    changed.accept(null, state);
    android.util.Log.i("ArriveOnNavi", state);
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
