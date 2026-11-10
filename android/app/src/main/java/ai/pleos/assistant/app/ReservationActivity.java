package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.CandidateSummary;
import ai.pleos.assistant.ux.DrivingRestrictionController;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReservationActivity extends ComponentActivity {
  private ReservationViewModel model;
  private DrivingRestrictionController driving;
  private PreviewPanel previewPanel;
  private Bundle previewDraft;
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
    previewDraft = saved;
    if (saved != null) {
      adultsInput = saved.getString("adultsInput", "2");
      childrenInput = saved.getString("childrenInput", "1");
      parkingInput = saved.getBoolean("parkingInput", true);
      showConditions = saved.getBoolean("showConditions", false);
    }
    model = new ViewModelProvider(this).get(ReservationViewModel.class);
    driving = new DrivingRestrictionController(CarRestrictionSource.create(this), model::driving);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (model.previewOpen()) {
                  model.closePreview();
                  previewPanel = null;
                } else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                  setEnabled(true);
                }
              }
            });
    model.observe(this::render);
    render();
  }

  @Override
  protected void onStart() {
    super.onStart();
    model.observe(this::render);
    driving.start();
    render();
    model.run(MockRepository::connect);
  }

  @Override
  protected void onStop() {
    if (approval != null) approval.dismiss();
    model.observe(null);
    model.invalidatePreview("CLOSED");
    driving.stop();
    super.onStop();
  }

  void retryDriving() {
    driving.start();
  }

  void debugRestriction() {
    if (BuildConfig.DEBUG) driving.addDebugRestriction();
  }

  void debugFailure() {
    if (BuildConfig.DEBUG) driving.fail(DrivingRestrictionController.Reason.DEBUG_FAILURE);
  }

  String drivingLabel() {
    var value = model.driving();
    int resource =
        switch (value.reason()) {
          case PLATFORM ->
              value.restriction().allowsInput()
                  ? R.string.driving_allowed
                  : R.string.driving_restricted;
          case CONNECTING -> R.string.driving_connecting;
          case UNSUPPORTED, MISSING_LIBRARY -> R.string.driving_unsupported;
          case PERMISSION_DENIED -> R.string.driving_permission;
          case SERVICE_DISCONNECTED -> R.string.driving_disconnected;
          case CONNECT_TIMEOUT -> R.string.driving_timeout;
          case DEBUG_RESTRICTED -> R.string.driving_debug_restricted;
          case DEBUG_FAILURE -> R.string.driving_debug_failed;
          case STOPPED -> R.string.driving_stopped;
          default -> R.string.driving_failed;
        };
    return getString(resource);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("adultsInput", adults == null ? adultsInput : adults.getText().toString());
    out.putString(
        "childrenInput", children == null ? childrenInput : children.getText().toString());
    out.putBoolean("parkingInput", parking == null ? parkingInput : parking.isChecked());
    out.putBoolean("showConditions", showConditions);
    if (previewPanel != null) previewPanel.save(out);
    super.onSaveInstanceState(out);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    VehicleUi.text(view, size);
    content.addView(view);
  }

  private void text(int resource) {
    text(getString(resource), 24);
  }

  private void button(int resource, boolean enabled, View.OnClickListener action) {
    Button button = new Button(this);
    button.setText(resource);
    VehicleUi.button(
        button,
        resource == R.string.search
            || resource == R.string.auth_create
            || resource == R.string.book
            || resource == R.string.change
            || resource == R.string.inquire);
    button.setEnabled(enabled);
    button.setOnClickListener(action);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(4), 0, dp(4));
    content.addView(button, params);
  }

  private EditText number(int label, String initial) {
    text(label);
    EditText field = new EditText(this);
    field.setContentDescription(getString(label));
    field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setText(initial);
    field.setMinHeight(dp(72));
    VehicleUi.input(field);
    field.setEnabled(model.canWrite());
    content.addView(field);
    return field;
  }

  private void render() {
    if (model.previewOpen()) {
      if (previewPanel == null) {
        previewPanel = new PreviewPanel(this, model, previewDraft);
        previewDraft = null;
      }
      if (previewPanel.view.getParent() == null) setContentView(previewPanel.view);
      previewPanel.update();
      return;
    }
    previewPanel = null;
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
    content.setPadding(dp(24), dp(16), dp(24), dp(24));
    content.setBackgroundColor(getColor(R.color.ui_background));
    scroll.addView(content);
    setContentView(scroll);
    JSONObject state = model.snapshot();
    text(getString(R.string.ui_brand), 32);
    text(R.string.ui_mock);
    text(R.string.ui_trip);
    JSONObject searchState = state.optJSONObject("search");
    JSONObject request = searchState == null ? null : searchState.optJSONObject("request");
    String eta = request == null ? "" : request.optJSONObject("navigation").optString("eta");
    text(
        eta.isEmpty()
            ? getString(R.string.ui_trip_pending)
            : getString(R.string.ui_trip_time, time(eta)),
        22);
    text(
        getString(
            R.string.ui_party,
            request == null ? adultsInput : request.optJSONObject("party").optString("adults"),
            request == null ? childrenInput : request.optJSONObject("party").optString("children")),
        22);
    text(stageLabel(state));
    if (!model.restriction().allowsInput()) {
      text(drivingLabel(), 22);
      button(R.string.driving_retry, true, v -> retryDriving());
    }
    if (model.busy()) {
      android.widget.ProgressBar progress = new android.widget.ProgressBar(this);
      progress.setContentDescription(getString(R.string.busy));
      content.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(40)));
      text(R.string.busy);
    }
    if (model.failed())
      text(
          "AUTH_CREATE_REQUIRED".equals(model.failureCode())
              ? R.string.auth_create_notice
              : R.string.auth_recovery_notice);
    if (model.canCreateIdentity())
      button(
          R.string.auth_create,
          !model.busy() && model.restriction().allowsInput(),
          v -> model.run(MockRepository::createIdentity));
    else if (!model.connected() || model.failed()) {
      button(R.string.connect, !model.busy(), v -> model.run(MockRepository::connect));
      button(
          R.string.auth_refresh, model.canWrite(), v -> model.run(MockRepository::renewIdentity));
    }
    JSONObject activeCall = state.optJSONObject("call");
    JSONObject reservationState = state.optJSONObject("reservation");
    String stage =
        (reservationState == null ? "" : reservationState.toString())
            + state.optString("searchId")
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
      JSONObject pending = state.optJSONObject("pending");
      boolean timeRejected =
          pending != null
              && java.util.Set.of("INVALID_TIME", "STALE_NAVIGATION")
                  .contains(pending.optString("recoveryCode"));
      text(timeRejected ? R.string.pending_time_rejected : R.string.pending);
      if (pending != null
          && "/searches".equals(pending.optString("path"))
          && "searchId".equals(pending.optString("target"))) {
        text(R.string.reconcile_search_notice);
        button(
            R.string.reconcile_search,
            !model.busy() && model.restriction().allowsInput(),
            v -> model.run(MockRepository::reconcileSearch));
      }
      button(
          R.string.retry,
          !model.busy() && model.restriction().allowsInput(),
          v -> model.run(MockRepository::retry));
    }
    if (!state.has("pending") && state.has("searchReconciliations")) {
      JSONArray history = state.optJSONArray("searchReconciliations");
      if (history != null && history.length() > 0) {
        JSONObject last = history.optJSONObject(history.length() - 1);
        JSONObject receipt = last == null ? null : last.optJSONObject("result");
        if (receipt != null)
          text(
              "REJECTED_CLOSED".equals(receipt.optString("state"))
                  ? R.string.reconcile_search_closed
                  : R.string.reconcile_search_completed);
      }
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
        VehicleUi.text(parking, 24);
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
      if (activeCall != null || state.has("reservation") || state.has("pending"))
        button(R.string.refresh, !model.busy(), v -> model.run(MockRepository::refresh));
      if (state.has("search") && !state.has("reservationId")) candidates(state);
      button(R.string.preview_open, model.canWrite(), v -> model.openPreview());
      diagnostics();

    } catch (Exception malformed) {
      text(R.string.failure);
    }
    scroll.post(() -> scroll.scrollTo(0, restoredY));
  }

  private int stageLabel(JSONObject state) {
    if (state.has("pending")) return R.string.ui_stage_unknown;
    if (state.has("reservation")) return R.string.ui_stage_result;
    JSONObject call = state.optJSONObject("call");
    if (call != null) {
      if ("UNKNOWN".equals(call.optString("status"))) return R.string.ui_stage_unknown;
      if ("PENDING".equals(call.optString("status"))) return R.string.ui_stage_wait;
      if ("ANSWERED".equals(call.optString("status"))) return R.string.ui_stage_approval;
    }
    return state.has("search") ? R.string.ui_stage_candidates : R.string.ui_stage_conditions;
  }

  private void diagnostics() {
    if (!BuildConfig.DEBUG) return;
    Button toggle = new Button(this);
    VehicleUi.button(toggle, false);
    content.addView(toggle);
    LinearLayout root = content;
    LinearLayout details = VehicleUi.card(this);
    root.addView(details);
    content = details;
    text(R.string.ui_diagnostics_notice);
    text(drivingLabel(), 22);
    button(R.string.driving_retry, true, v -> retryDriving());
    button(R.string.driving_test_restricted, true, v -> debugRestriction());
    button(R.string.driving_test_failed, true, v -> debugFailure());
    button(R.string.connect, !model.busy(), v -> model.run(MockRepository::connect));
    button(R.string.auth_refresh, model.canWrite(), v -> model.run(MockRepository::renewIdentity));
    text(model.connected() ? R.string.connected : R.string.disconnected);
    text(R.string.auth_operator_notice);
    content = root;
    VehicleUi.disclosure(toggle, details, R.string.ui_diagnostics, R.string.ui_diagnostics_close);
  }

  private String factSummary(JSONObject facts, String key, String eta) {
    String value = CandidateSummary.fact(facts, key, Instant.now(), Instant.parse(eta));
    boolean estimated = value.startsWith("ESTIMATED:");
    if (estimated) value = value.substring("ESTIMATED:".length());
    String label =
        switch (value) {
          case "UNKNOWN" -> getString(R.string.ui_fact_unknown);
          case "EXPIRED" -> getString(R.string.ui_fact_expired);
          case "true" -> getString(R.string.ui_fact_yes);
          case "false" -> getString(R.string.ui_fact_no);
          default -> getString(R.string.ui_minutes, value);
        };
    return estimated ? getString(R.string.ui_estimated, label) : label;
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
    LinearLayout listRoot = content;
    LinearLayout row = new LinearLayout(this);
    boolean wide = getResources().getConfiguration().screenWidthDp >= 1100;
    row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    listRoot.addView(row);
    for (int i = 0; i < Math.min(3, candidates.length()); i++) {
      JSONObject candidate = candidates.getJSONObject(i);
      String id = candidate.getString("id");
      LinearLayout root = content;
      LinearLayout card = VehicleUi.card(this);
      row.addView(card);
      if (wide) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        layout.setMargins(dp(4), dp(8), dp(4), dp(8));
        card.setLayoutParams(layout);
      }
      content = card;
      text(candidate.getString("name"), 28);
      text(candidate.getString("cuisine"), 22);
      text(
          switch (CandidateSummary.state(candidate)) {
            case EXCLUDED -> R.string.ui_excluded;
            case REVIEW_REQUIRED -> R.string.ui_review;
            case REQUEST_AVAILABLE -> R.string.ui_request_available;
          });
      JSONObject facts = candidate.getJSONObject("facts");
      for (String key :
          new String[] {"parking", "childFriendly", "arrivalWaitMinutes", "reservationAvailable"})
        text(
            getString(
                R.string.ui_fact_row,
                label(key),
                factSummary(
                    facts,
                    key,
                    search.getJSONObject("request").getJSONObject("navigation").getString("eta"))),
            24);
      button(
          expandedFacts.contains(id) ? R.string.hide_facts : R.string.show_facts,
          true,
          v -> {
            if (!expandedFacts.remove(id)) expandedFacts.add(id);
            render();
          });
      if (expandedFacts.contains(id)) {
        text(getString(R.string.ui_missing, list(candidate.getJSONArray("missing"))), 22);
        text(
            getString(R.string.ui_exclusions, list(candidate.getJSONArray("exclusionReasons"))),
            22);
      }
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
      content = root;
    }
  }

  private void confirm(int title, String message, ReservationViewModel.Action action) {
    if (!model.canWrite()) return;
    TextView dialogTitle = new TextView(this);
    dialogTitle.setText(title);
    dialogTitle.setTextSize(28);
    dialogTitle.setTextColor(getColor(R.color.ui_text));
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
    if (approval.getWindow() != null)
      approval
          .getWindow()
          .setBackgroundDrawable(
              new android.graphics.drawable.ColorDrawable(getColor(R.color.ui_surface)));
    TextView messageView = approval.findViewById(android.R.id.message);
    if (messageView != null) messageView.setTextSize(24);
    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
      Button actionButton = approval.getButton(which);
      VehicleUi.button(actionButton, which == AlertDialog.BUTTON_POSITIVE);
    }
    if (approval.getWindow() != null)
      approval
          .getWindow()
          .setLayout(Math.min(dp(1200), (int) (getWindow().getDecorView().getWidth() * 0.94)), -2);
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
    // Server-issued installation tokens have no MOCK_OPERATOR role.
  }
}
