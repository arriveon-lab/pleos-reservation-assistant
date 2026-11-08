package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.MockRepository;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReservationActivity extends ComponentActivity {
  private ReservationViewModel model;
  private LinearLayout content;
  private AlertDialog approval;
  private String adultsInput = "2";
  private String childrenInput = "1";
  private boolean parkingInput = true;
  private EditText adults;
  private EditText children;
  private CheckBox parking;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    model = new ViewModelProvider(this).get(ReservationViewModel.class);
    model.observe(this::render);
    render();
  }

  @Override
  protected void onStart() {
    super.onStart();
    model.observe(this::render);
    render();
    model.run(MockRepository::connect);
  }

  @Override
  protected void onStop() {
    if (approval != null) approval.dismiss();
    model.observe(null);
    model.restrict(MockRepository.Restriction.UNKNOWN);
    super.onStop();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setPadding(0, dp(8), 0, dp(8));
    content.addView(view);
  }

  private void text(int resource) {
    text(getString(resource), 18);
  }

  private void button(int resource, boolean enabled, View.OnClickListener action) {
    Button button = new Button(this);
    button.setText(resource);
    button.setMinHeight(dp(56));
    button.setEnabled(enabled);
    button.setOnClickListener(action);
    content.addView(button);
  }

  private EditText number(int label, String initial) {
    text(label);
    EditText field = new EditText(this);
    field.setContentDescription(getString(label));
    field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setText(initial);
    field.setMinHeight(dp(56));
    field.setEnabled(model.canWrite());
    content.addView(field);
    return field;
  }

  private void render() {
    if (adults != null) adultsInput = adults.getText().toString();
    if (children != null) childrenInput = children.getText().toString();
    if (parking != null) parkingInput = parking.isChecked();
    adults = null;
    children = null;
    parking = null;
    if (approval != null && !model.canWrite()) approval.dismiss();
    ScrollView scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), dp(32), dp(24), dp(24));
    scroll.addView(content);
    setContentView(scroll);
    text(getString(R.string.title), 28);
    text(R.string.disclosure);
    int restriction =
        switch (model.restriction()) {
          case UNKNOWN -> R.string.restriction_unknown;
          case RESTRICTED -> R.string.restriction_blocked;
          case SIMULATED_PARKED -> R.string.restriction_parked;
        };
    text(restriction);
    if (BuildConfig.DEBUG) {
      button(
          R.string.simulate_parked,
          true,
          v -> model.restrict(MockRepository.Restriction.SIMULATED_PARKED));
      button(
          R.string.simulate_restricted,
          true,
          v -> model.restrict(MockRepository.Restriction.RESTRICTED));
      button(
          R.string.simulate_unknown, true, v -> model.restrict(MockRepository.Restriction.UNKNOWN));
    }
    button(R.string.connect, !model.busy(), v -> model.run(MockRepository::connect));
    button(R.string.refresh, !model.busy(), v -> model.run(MockRepository::refresh));
    if (model.busy()) text(R.string.busy);
    if (model.failed()) text(R.string.failure);
    JSONObject state = model.snapshot();
    if (state.has("pending")) {
      text(R.string.pending);
      button(
          R.string.retry,
          !model.busy() && model.restriction() == MockRepository.Restriction.SIMULATED_PARKED,
          v -> model.run(MockRepository::retry));
    }
    try {
      if (!state.has("reservationId")) {
        text(R.string.conditions);
        text(R.string.synthetic_trip);
        adults = number(R.string.adults, adultsInput);
        children = number(R.string.children, childrenInput);
        parking = new CheckBox(this);
        parking.setText(R.string.parking);
        parking.setChecked(parkingInput);
        parking.setEnabled(model.canWrite());
        parking.setMinHeight(dp(56));
        content.addView(parking);
        button(
            R.string.search,
            model.canWrite(),
            v -> {
              try {
                int adultCount = Integer.parseInt(adults.getText().toString());
                int childCount = Integer.parseInt(children.getText().toString());
                boolean requiredParking = parking.isChecked();
                model.run(r -> r.search(adultCount, childCount, requiredParking));
              } catch (NumberFormatException error) {
                text(R.string.invalid_input);
              }
            });
      }
      if (state.has("search")) candidates(state);
      if (state.has("reservation")) reservation(state.getJSONObject("reservation"));
      if (state.has("call")) call(state.getJSONObject("call"));
    } catch (Exception malformed) {
      text(R.string.failure);
    }
  }

  private String list(JSONArray array) throws Exception {
    if (array.length() == 0) return getString(R.string.none);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < array.length(); i++) {
      if (i > 0) text.append(", ");
      text.append(array.getString(i));
    }
    return text.toString();
  }

  private String time(String raw) {
    if (raw == null || raw.equals("null") || raw.isEmpty())
      return getString(R.string.not_confirmed);
    try {
      return DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'")
          .withZone(ZoneId.of("Asia/Seoul"))
          .format(Instant.parse(raw));
    } catch (RuntimeException invalid) {
      return raw;
    }
  }

  private void candidates(JSONObject state) throws Exception {
    text(R.string.candidates);
    text(R.string.wait_notice);
    JSONObject search = state.getJSONObject("search");
    JSONArray candidates = search.getJSONArray("candidates");
    if (candidates.length() == 0) text(R.string.empty);
    for (int i = 0; i < candidates.length(); i++) {
      JSONObject candidate = candidates.getJSONObject(i);
      String id = candidate.getString("id");
      text(
          getString(
              R.string.candidate,
              candidate.getString("name"),
              candidate.getString("cuisine"),
              getString(candidate.getBoolean("eligible") ? R.string.yes : R.string.no),
              getString(candidate.getBoolean("bookable") ? R.string.yes : R.string.no),
              list(candidate.getJSONArray("missing")),
              list(candidate.getJSONArray("exclusionReasons"))),
          18);
      JSONObject facts = candidate.getJSONObject("facts");
      Iterator<String> keys = facts.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        JSONObject fact = facts.getJSONObject(key);
        text(
            getString(
                R.string.fact,
                key,
                fact.optString("value", "UNKNOWN"),
                fact.optString("source"),
                fact.optString("certainty"),
                fact.optString("scope"),
                time(fact.optString("observedAt")),
                time(fact.optString("appliesAt")),
                time(fact.optString("validUntil"))),
            14);
      }
      boolean available = model.canWrite() && !state.has("reservationId");
      button(
          R.string.inquire,
          available && candidate.getBoolean("eligible"),
          v -> model.run(r -> r.inquire(id)));
      JSONObject party = search.getJSONObject("request").getJSONObject("party");
      String eta = search.getJSONObject("request").getJSONObject("navigation").getString("eta");
      String message =
          getString(
              R.string.approve_body,
              candidate.getString("name"),
              party.getInt("adults"),
              party.getInt("children"),
              time(eta));
      button(
          R.string.book,
          available && candidate.getBoolean("bookable"),
          v -> confirm(R.string.approve_title, message, r -> r.book(id, true)));
    }
  }

  private void confirm(int title, String message, ReservationViewModel.Action action) {
    if (!model.canWrite()) return;
    approval =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.approve,
                (dialog, which) -> {
                  if (model.canWrite()) model.run(action);
                })
            .create();
    approval.show();
  }

  private void reservation(JSONObject value) throws Exception {
    text(R.string.result);
    int status =
        switch (value.getString("status")) {
          case "PENDING" -> R.string.status_pending;
          case "CONFIRMED" -> R.string.status_confirmed;
          case "REJECTED" -> R.string.status_rejected;
          default -> R.string.status_unknown;
        };
    text(status);
    text(
        getString(
            R.string.reservation,
            time(value.getString("originalRequestedTime")),
            time(value.optString("confirmedTime")),
            value.getInt("totalAdditionalCostKrw")),
        18);
    if (!value.isNull("pendingChangeCallId")) text(R.string.change_unresolved);
    boolean allowed =
        model.canWrite()
            && "CONFIRMED".equals(value.getString("status"))
            && value.isNull("pendingChangeCallId");
    String confirmed = value.optString("confirmedTime");
    if (allowed) {
      String requested = Instant.parse(confirmed).plus(15, ChronoUnit.MINUTES).toString();
      button(
          R.string.change,
          true,
          v ->
              confirm(
                  R.string.change_title,
                  getString(R.string.change_body, time(confirmed), time(requested)),
                  r -> r.change(true)));
    }
  }

  private void call(JSONObject value) throws Exception {
    text(
        getString(
            R.string.call,
            value.getString("purpose"),
            value.getString("status"),
            time(value.getString("requestedTime"))),
        18);
    if (!BuildConfig.DEBUG) return;
    text(R.string.operator);
    boolean pending = model.canWrite() && "PENDING".equals(value.getString("status"));
    String outcome = "INQUIRY".equals(value.getString("purpose")) ? "ANSWERED" : "ACCEPTED";
    button(R.string.answer, pending, v -> model.run(r -> r.simulate(outcome)));
    button(R.string.reject, pending, v -> model.run(r -> r.simulate("REJECTED")));
    button(R.string.unknown, pending, v -> model.run(r -> r.simulate("UNKNOWN")));
  }
}
