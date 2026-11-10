package ai.pleos.assistant.probe;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.TripInfo;
import ai.pleos.playground.navi.data.TripRequest;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import kotlin.Unit;
import org.json.JSONException;
import org.json.JSONObject;

/** Developer probe only. No server, telephone provider, location persistence, or ETA conversion. */
public final class ProbeActivity extends Activity {
  private NaviHelper helper;
  private TextView output;
  private Button query;
  private volatile boolean initialized;
  private volatile boolean closed;
  private volatile DestinationInfo destination;

  private final NaviHelperEventListener listener =
      new NaviHelperEventListener() {
        @Override
        public void onDestinationInfo(DestinationInfo info) {
          destination = info;
          record("destinationInfo", "hasDestination", true);
          runOnUiThread(() -> query.setEnabled(!closed));
        }

        @Override
        public void onDestinationChanged() {
          destination = null;
          record("destinationChanged", "hasDestination", false);
          runOnUiThread(() -> query.setEnabled(false));
          if (initialized && !closed) helper.getDestinationInfo();
        }

        @Override
        public void onDestinationArrived(DestinationArrivedInfo info) {
          record("destinationArrived", "received", true);
        }

        @Override
        public void onTripInfo(TripInfo info) {
          // Official source says seconds; duration vs epoch/time-of-day remains unverified.
          record("tripInfo", "rawEtaSeconds", info.getEta());
        }

        @Override
        public void onError(NaviErrorCode code) {
          record("sdkError", "code", String.valueOf(code));
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
    Button initialize = new Button(this);
    initialize.setText(R.string.initialize_navi);
    layout.addView(initialize);
    query = new Button(this);
    query.setText(R.string.request_trip);
    query.setEnabled(false);
    layout.addView(query);
    output = new TextView(this);
    output.setTextSize(18);
    layout.addView(output);
    setContentView(layout);
    initialize.setOnClickListener(
        view -> {
          initialize.setEnabled(false);
          helper = new NaviHelper(getApplicationContext());
          helper.initialize(
              () -> {
                if (closed) {
                  helper.release();
                  return Unit.INSTANCE;
                }
                initialized = true;
                helper.addListener(listener);
                record("initialized", "sdkVersion", "3.0.5");
                helper.getDestinationInfo();
                return Unit.INSTANCE;
              },
              error -> {
                record("initializeFailed", "errorType", error.getClass().getSimpleName());
                return Unit.INSTANCE;
              });
        });
    query.setOnClickListener(
        view -> {
          DestinationInfo d = destination;
          if (!initialized || d == null || closed) return;
          record("tripRequested", "timeBasis", "UNVERIFIED");
          helper.requestTrip(
              new TripRequest(
                  d.getLatitude(), d.getLongitude(), d.getPoiId(), d.getName(), d.getPoiSubId()));
        });
  }

  private void record(String event, String key, Object value) {
    try {
      JSONObject line =
          new JSONObject()
              .put("measurementMode", "SDK_PROBE")
              .put("source", "PLEOS")
              .put("event", event)
              .put("observedAt", Instant.now().toString())
              .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
              .put("etaTimeBasis", "UNVERIFIED")
              .put(key, value);
      Log.i("PleosNaviProbe", line.toString());
      if (!closed) runOnUiThread(() -> output.setText(line.toString()));
    } catch (JSONException error) {
      Log.e("PleosNaviProbe", "Cannot encode probe event");
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    record("lifecycle", "state", "STARTED");
  }

  @Override
  public void onStop() {
    record("lifecycle", "state", "STOPPED");
    super.onStop();
  }

  @Override
  public void onDestroy() {
    closed = true;
    if (helper != null) {
      if (initialized) helper.removeListener(listener);
      helper.release();
    }
    super.onDestroy();
  }
}
