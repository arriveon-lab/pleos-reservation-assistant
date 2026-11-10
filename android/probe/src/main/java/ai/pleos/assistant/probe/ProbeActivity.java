package ai.pleos.assistant.probe;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.TripInfo;
import ai.pleos.playground.navi.data.TripRequest;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.util.UUID;
import kotlin.Unit;
import org.json.JSONException;
import org.json.JSONObject;

/** Developer probe only. No server, telephone provider, location persistence, or ETA conversion. */
public final class ProbeActivity extends Activity {
  private final ProbeState<DestinationInfo> state = new ProbeState<>();
  private final String probeSessionId = UUID.randomUUID().toString();
  private NaviHelper helper;
  private boolean listenerRegistered;
  private Button initialize;
  private Button refresh;
  private Button query;
  private Button reroute;
  private TextView output;
  private boolean foreground;
  private boolean initializationAttempted;

  private final NaviHelperEventListener listener =
      new NaviHelperEventListener() {
        @Override
        public void onDestinationInfo(DestinationInfo info) {
          observe(
              "destinationInfo",
              "destinationValidation",
              validDestination(info) ? "VALID_FIELDS" : "MISSING_OR_INVALID",
              () -> state.receiveDestination(validDestination(info) ? info : null));
        }

        @Override
        public void onDestinationChanged() {
          observe("destinationChanged", "received", true, () -> invalidate(true));
        }

        @Override
        public void onDestinationArrived(DestinationArrivedInfo info) {
          observe("destinationArrived", "received", true, () -> invalidate(false));
        }

        @Override
        public void onRouteCancelled() {
          observe("routeCancelled", "received", true, () -> invalidate(false));
        }

        @Override
        public void onRouteStarted(RouteStartInfo info) {
          observe("routeStarted", "received", true, () -> invalidate(false));
        }

        @Override
        public void onRoutingCompleted() {
          observe("routingCompleted", "received", true, () -> invalidate(true));
        }

        @Override
        public void onReRouteEnded() {
          observe("reRouteEnded", "received", true, () -> invalidate(true));
        }

        @Override
        public void onTripInfo(TripInfo info) {
          // navi-data 3.0.4 TripInfo.kt: int seconds, no correlation ID or time basis.
          // Never assign this response to the current destination, even after refresh.
          observe(
              "tripInfo",
              "rawEtaSeconds",
              info == null ? JSONObject.NULL : info.getEta(),
              () -> {});
        }

        @Override
        public void onError(NaviErrorCode code) {
          observe("sdkError", "code", String.valueOf(code), () -> invalidate(false));
        }
      };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    layout.setPadding(padding, padding, padding, padding);
    TextView title = new TextView(this);
    title.setText(R.string.probe_description);
    title.setTextSize(24);
    layout.addView(title);
    initialize = new Button(this);
    initialize.setText(R.string.initialize_navi);
    layout.addView(initialize);
    refresh = new Button(this);
    refresh.setText(R.string.refresh_destination);
    refresh.setEnabled(false);
    layout.addView(refresh);
    query = new Button(this);
    query.setText(R.string.request_trip);
    query.setEnabled(false);
    layout.addView(query);
    reroute = new Button(this);
    reroute.setText(R.string.request_reroute);
    reroute.setEnabled(false);
    layout.addView(reroute);
    output = new TextView(this);
    output.setTextSize(18);
    layout.addView(output);
    setContentView(layout);
    initialize.setOnClickListener(view -> initializeHelper());
    refresh.setOnClickListener(view -> requestDestination());
    query.setOnClickListener(
        view -> {
          DestinationInfo d = state.destination();
          if (d == null || helper == null || state.isClosed()) return;
          record("tripRequested", "responseAssociation", "UNVERIFIED");
          invokeSdk(
              () ->
                  helper.requestTrip(
                      new TripRequest(
                          d.getLatitude(),
                          d.getLongitude(),
                          d.getPoiId(),
                          d.getName(),
                          d.getPoiSubId())));
        });
    reroute.setOnClickListener(
        view -> {
          if (state.destination() == null || helper == null || state.isClosed()) return;
          if (!reroutePermissionGranted()) {
            reroute.setEnabled(false);
            record("reRouteRejected", "failure", "PERMISSION_DENIED");
            return;
          }
          // NaviHelper 3.0.5 requestReRoute(): official Routes guide, active guidance only.
          // Clear cached destination before calling: callbacks may arrive synchronously.
          invalidate(false);
          record("reRouteRequested", "responseAssociation", "UNVERIFIED");
          invokeSdk(() -> helper.requestReRoute());
        });
  }

  private void initializeHelper() {
    if (state.isClosed() || initializationAttempted) return;
    initializationAttempted = true;
    initialize.setEnabled(false);
    record(
        "permission",
        "naviRouteGranted",
        checkSelfPermission("pleos.car.permission.NAVI_ROUTE")
            == PackageManager.PERMISSION_GRANTED);
    record("permission", "naviRouteSearchGranted", reroutePermissionGranted());
    try {
      NaviHelper attempt = new NaviHelper(getApplicationContext());
      helper = attempt;
      attempt.initialize(
          () -> {
            runOnUiThread(
                () -> {
                  if (state.isClosed() || helper != attempt) return;
                  invokeSdk(
                      () -> {
                        attempt.addListener(listener);
                        listenerRegistered = true;
                        state.connect();
                        refresh.setEnabled(true);
                        record("initialized", "sdkVersion", "3.0.5");
                        requestDestination();
                      });
                });
            return Unit.INSTANCE;
          },
          error -> {
            runOnUiThread(
                () -> {
                  if (!state.isClosed() && helper == attempt) fail("initializeFailed", error);
                });
            return Unit.INSTANCE;
          });
    } catch (RuntimeException error) {
      fail("initializeFailed", error);
    }
  }

  private static boolean validDestination(DestinationInfo info) {
    return info != null
        && ProbeState.validCoordinates(info.getLatitude(), info.getLongitude())
        && info.getName() != null
        && !info.getName().isBlank()
        && info.getPoiId() != null;
  }

  private boolean reroutePermissionGranted() {
    return checkSelfPermission("pleos.car.permission.NAVI_ROUTE_SEARCH")
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestDestination() {
    if (!state.requestDestination()) return;
    query.setEnabled(false);
    reroute.setEnabled(false);
    record("destinationRequested", "responseAssociation", "UNVERIFIED");
    invokeSdk(() -> helper.getDestinationInfo());
  }

  private void invalidate(boolean refreshDestination) {
    state.invalidate();
    query.setEnabled(false);
    reroute.setEnabled(false);
    if (refreshDestination) requestDestination();
  }

  private void invokeSdk(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException error) {
      fail("sdkCallFailed", error);
    }
  }

  private void fail(String event, Throwable error) {
    state.disconnect();
    query.setEnabled(false);
    reroute.setEnabled(false);
    refresh.setEnabled(false);
    record(
        event,
        "failure",
        error instanceof SecurityException ? "PERMISSION_DENIED" : "UNAVAILABLE_UNKNOWN");
    releaseHelper();
    // Retry by recreating the Activity: old SDK callbacks carry no helper/session identifier.
  }

  private void releaseHelper() {
    NaviHelper previous = helper;
    helper = null;
    if (previous == null) return;
    try {
      if (listenerRegistered) previous.removeListener(listener);
    } catch (RuntimeException error) {
      record("listenerRemovalFailed", "errorType", error.getClass().getSimpleName());
    } finally {
      listenerRegistered = false;
      try {
        previous.release();
      } catch (RuntimeException error) {
        record("releaseFailed", "errorType", error.getClass().getSimpleName());
      }
    }
  }

  private void observe(String event, String key, Object value, Runnable update) {
    // Timestamp at callback entry, before posting to main. Rendering time is not observation time.
    String observedAt = Instant.now().toString();
    long elapsedRealtimeMs = SystemClock.elapsedRealtime();
    runOnUiThread(
        () -> {
          boolean ignored = state.isClosed();
          if (!ignored) {
            update.run();
            query.setEnabled(state.destination() != null);
            reroute.setEnabled(state.destination() != null && reroutePermissionGranted());
          }
          record(event, key, value, observedAt, elapsedRealtimeMs, ignored);
        });
  }

  private void record(String event, String key, Object value) {
    record(
        event,
        key,
        value,
        Instant.now().toString(),
        SystemClock.elapsedRealtime(),
        state.isClosed());
  }

  private void record(
      String event,
      String key,
      Object value,
      String observedAt,
      long elapsedRealtimeMs,
      boolean ignored) {
    try {
      JSONObject line =
          new JSONObject()
              .put("measurementMode", "SDK_PROBE")
              .put("probeSessionId", probeSessionId)
              .put("sdkVersion", "3.0.5")
              .put("source", "PLEOS")
              .put("event", event)
              .put("observedAt", observedAt)
              .put("elapsedRealtimeMs", elapsedRealtimeMs)
              .put("etaTimeBasis", "UNVERIFIED")
              .put("responseAssociation", "UNVERIFIED")
              .put("routeRevisionAtProcessing", state.routeRevision())
              .put("foregroundAtProcessing", foreground)
              .put("ignoredAfterClose", ignored)
              .put("destinationUsableAtProcessing", state.destination() != null)
              .put(key, value);
      if ("tripInfo".equals(event)) {
        line.put(
            "rawEtaSign",
            value instanceof Number number
                ? (number.intValue() < 0
                    ? "NEGATIVE"
                    : number.intValue() == 0 ? "ZERO" : "POSITIVE")
                : "MISSING");
      }
      Log.i("PleosNaviProbe", line.toString());
      if (!state.isClosed()) output.setText(line.toString());
    } catch (JSONException error) {
      Log.e("PleosNaviProbe", "Cannot encode probe event");
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    foreground = true;
    record("lifecycle", "state", "STARTED");
  }

  @Override
  public void onStop() {
    foreground = false;
    record("lifecycle", "state", "STOPPED");
    super.onStop();
  }

  @Override
  public void onDestroy() {
    state.close();
    releaseHelper();
    super.onDestroy();
  }
}
