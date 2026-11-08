package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.MockRepository;
import android.app.AlertDialog;
import android.graphics.Color;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReservationActivity extends ComponentActivity {
  private ReservationViewModel model;
  private LinearLayout content;
  private ScrollView scroll;
  private boolean showConditions;
  private final Set<String> expandedFacts = new HashSet<>();
  private String displayedStage = "";
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
    view.setTextColor(Color.rgb(24, 36, 52));
    view.setPadding(0, dp(8), 0, dp(8));
    content.addView(view);
  }

  private void text(int resource) {
    text(getString(resource), 24);
  }

  private void button(int resource, boolean enabled, View.OnClickListener action) {
    Button button = new Button(this);
    button.setText(resource);
    button.setMinHeight(dp(72));
    button.setTextSize(22);
    button.setAllCaps(false);
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
    field.setMinHeight(dp(72));
    field.setTextSize(24);
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
    int previousY = scroll == null ? 0 : scroll.getScrollY();
    scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(32), dp(24), dp(32), dp(32));
    content.setBackgroundColor(Color.rgb(247, 249, 252));
    scroll.addView(content);
    setContentView(scroll);
    text(getString(R.string.title), 32);
    text(R.string.disclosure);
    int restriction =
        switch (model.restriction()) {
          case UNKNOWN -> R.string.restriction_unknown;
          case RESTRICTED -> R.string.restriction_blocked;
          case SIMULATED_PARKED -> R.string.restriction_parked;
        };
    text(restriction);
    if (BuildConfig.DEBUG) {
      LinearLayout parent = content;
      content = new LinearLayout(this);
      content.setOrientation(LinearLayout.HORIZONTAL);
      parent.addView(content);
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
      for (int i = 0; i < content.getChildCount(); i++)
        content.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
      content = parent;
    }
    button(R.string.connect, !model.busy(), v -> model.run(MockRepository::connect));
    button(R.string.refresh, !model.busy(), v -> model.run(MockRepository::refresh));
    text(model.connected() ? R.string.connected : R.string.disconnected);
    if (model.busy()) text(R.string.busy);
    if (model.failed()) text(R.string.failure);
    JSONObject state = model.snapshot();
    JSONObject activeCall = state.optJSONObject("call");
    String stage =
        state.optString("searchId")
            + state.optString("reservationId")
            + (activeCall == null
                ? ""
                : activeCall.optString("id") + activeCall.optString("status"));
    boolean unresolvedCall =
        activeCall != null
            && ("PENDING".equals(activeCall.optString("status"))
                || "UNKNOWN".equals(activeCall.optString("status")));
    int restoredY = stage.equals(displayedStage) ? previousY : 0;
    displayedStage = stage;
    if (state.has("pending")) {
      text(R.string.pending);
      button(
          R.string.retry,
          !model.busy() && model.restriction() == MockRepository.Restriction.SIMULATED_PARKED,
          v -> model.run(MockRepository::retry));
    }
    try {
      if (state.has("reservation")) reservation(state.getJSONObject("reservation"));
      if (activeCall != null) call(activeCall);
      if (state.has("search") && !state.has("reservationId")) {
        button(
            showConditions ? R.string.hide_conditions : R.string.edit_conditions,
            model.canWrite() && !unresolvedCall,
            v -> {
              showConditions = !showConditions;
              render();
            });
      }
      if (!state.has("reservationId") && (!state.has("search") || showConditions)) {
        text(R.string.conditions);
        text(R.string.synthetic_trip);
        adults = number(R.string.adults, adultsInput);
        children = number(R.string.children, childrenInput);
        parking = new CheckBox(this);
        parking.setText(R.string.parking);
        parking.setChecked(parkingInput);
        parking.setEnabled(model.canWrite());
        parking.setMinHeight(dp(72));
        parking.setTextSize(24);
        content.addView(parking);
        button(
            R.string.search,
            model.canWrite() && !unresolvedCall,
            v -> {
              try {
                int adultCount = Integer.parseInt(adults.getText().toString());
                int childCount = Integer.parseInt(children.getText().toString());
                boolean requiredParking = parking.isChecked();
                showConditions = false;
                model.run(r -> r.search(adultCount, childCount, requiredParking));
              } catch (NumberFormatException error) {
                text(R.string.invalid_input);
              }
            });
      }
      if (state.has("search")) candidates(state);

    } catch (Exception malformed) {
      text(R.string.failure);
    }
    scroll.post(() -> scroll.scrollTo(0, restoredY));
  }

  private String list(JSONArray array) throws Exception {
    if (array.length() == 0) return getString(R.string.none);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < array.length(); i++) {
      if (i > 0) text.append(", ");
      text.append(label(array.getString(i)));
    }
    return text.toString();
  }

  private String label(String raw) {
    int resource =
        switch (raw) {
          case "INQUIRY" -> R.string.call_inquiry;
          case "BOOKING" -> R.string.call_booking;
          case "CHANGE" -> R.string.call_change;
          case "PENDING" -> R.string.call_pending;
          case "ANSWERED" -> R.string.call_answered;
          case "ACCEPTED" -> R.string.call_accepted;
          case "REJECTED" -> R.string.call_rejected;
          case "UNKNOWN" -> R.string.call_unknown;
          case "parking" -> R.string.feature_parking;
          case "childFriendly" -> R.string.feature_children;
          case "seatsAvailable" -> R.string.feature_seats;
          case "reservationAvailable" -> R.string.feature_booking;
          case "nowWaitMinutes" -> R.string.feature_now;
          case "arrivalWaitMinutes" -> R.string.feature_arrival;
          default -> 0;
        };
    return resource == 0 ? raw : getString(resource);
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
          24);
      JSONObject facts = candidate.getJSONObject("facts");
      button(
          expandedFacts.contains(id) ? R.string.hide_facts : R.string.show_facts,
          true,
          v -> {
            if (!expandedFacts.remove(id)) expandedFacts.add(id);
            render();
          });
      Iterator<String> keys = expandedFacts.contains(id) ? facts.keys() : new JSONObject().keys();
      while (keys.hasNext()) {
        String key = keys.next();
        JSONObject fact = facts.getJSONObject(key);
        text(
            getString(
                R.string.fact,
                label(key),
                fact.optString("value", "UNKNOWN"),
                fact.optString("source"),
                fact.optString("certainty"),
                fact.optString("scope"),
                time(fact.optString("observedAt")),
                time(fact.optString("appliesAt")),
                time(fact.optString("validUntil"))),
            20);
      }
      JSONObject lastCall = state.optJSONObject("call");
      boolean unresolved =
          lastCall != null
              && ("PENDING".equals(lastCall.optString("status"))
                  || "UNKNOWN".equals(lastCall.optString("status")));
      boolean available = model.canWrite() && !state.has("reservationId") && !unresolved;
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
    TextView dialogTitle = new TextView(this);
    dialogTitle.setText(title);
    dialogTitle.setTextSize(28);
    dialogTitle.setTextColor(Color.rgb(24, 36, 52));
    dialogTitle.setPadding(dp(24), dp(24), dp(24), dp(12));
    approval =
        new AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.approve,
                (dialog, which) -> {
                  if (model.canWrite()) model.run(action);
                })
            .create();
    approval.show();
    TextView messageView = approval.findViewById(android.R.id.message);
    if (messageView != null) messageView.setTextSize(24);
    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
      Button actionButton = approval.getButton(which);
      actionButton.setTextSize(22);
      actionButton.setMinHeight(dp(72));
      actionButton.setAllCaps(false);
    }
    if (approval.getWindow() != null)
      approval
          .getWindow()
          .setLayout(
              Math.min(dp(1200), (int) (getResources().getDisplayMetrics().widthPixels * 0.9)), -2);
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
        24);
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
            label(value.getString("purpose")),
            label(value.getString("status")),
            time(value.getString("requestedTime"))),
        24);
    if (!BuildConfig.DEBUG || !"PENDING".equals(value.getString("status"))) return;
    text(R.string.operator);
    boolean pending = model.canWrite() && "PENDING".equals(value.getString("status"));
    String outcome = "INQUIRY".equals(value.getString("purpose")) ? "ANSWERED" : "ACCEPTED";
    button(R.string.answer, pending, v -> model.run(r -> r.simulate(outcome)));
    button(R.string.reject, pending, v -> model.run(r -> r.simulate("REJECTED")));
    button(R.string.unknown, pending, v -> model.run(r -> r.simulate("UNKNOWN")));
  }
}
