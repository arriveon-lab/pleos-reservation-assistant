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
  private ai.pleos.assistant.ux.GleoSearchCommand voiceCommand;
  private long voiceDeadline;
  private boolean voiceInputOnly;
  private boolean voiceResumed;
  private boolean voiceOpen;
  private NavigationDestinationAdapter navigation;
  private final android.os.Handler voiceHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable voiceTimeout = () -> finishVoice(false);
  private ReservationViewModel model;
  private DrivingRestrictionController driving;
  private PreviewPanel previewPanel;
  private DestinationPanel destinationPanel;
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
    if (saved == null) receiveVoice(getIntent());
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
                if (model.destinationOpen()) {
                  model.closeDestination();
                  destinationPanel = null;
                } else if (model.previewOpen()) {
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
    navigation = new NavigationDestinationAdapter(this, model::navigation);
    navigation.start();
    render();
    model.run(MockRepository::connect);
  }

  void retryNavigation() {
    if (navigation == null || !model.restriction().allowsInput()) return;
    navigation.close();
    navigation = new NavigationDestinationAdapter(this, model::navigation);
    navigation.start();
  }

  @Override
  protected void onStop() {
    voiceResumed = false;
    voiceOpen = false;
    finishVoice(false);
    if (approval != null) approval.dismiss();
    model.observe(null);
    model.invalidatePreview("CLOSED");
    model.invalidateDestination("CLOSED");
    driving.stop();
    if (navigation != null) navigation.close();
    navigation = null;
    super.onStop();
  }

  @Override
  protected void onNewIntent(android.content.Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    receiveVoice(intent);
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    voiceResumed = true;
    render();
  }

  @Override
  protected void onPause() {
    voiceResumed = false;
    super.onPause();
  }

  private void receiveVoice(android.content.Intent intent) {
    if (GleoIntentAdapter.OPEN.equals(intent.getAction())
        || (getPackageName() + "." + GleoIntentAdapter.OPEN).equals(intent.getAction())) {
      intent.setAction(null);
      voiceOpen = true;
      android.util.Log.i("ArriveOnGleo", "OPEN_COMMAND_RECEIVED");
      return;
    }
    String action = ai.pleos.assistant.ux.GleoSearchCommand.ACTION;
    // Gleo 4.2.0.15 on Pleos 2.1.0 prefixes the package; official examples use bare action.
    boolean inputOnly =
        GleoIntentAdapter.SET_PARTY.equals(intent.getAction())
            || (getPackageName() + "." + GleoIntentAdapter.SET_PARTY).equals(intent.getAction());
    if (!inputOnly
        && !action.equals(intent.getAction())
        && !(getPackageName() + "." + action).equals(intent.getAction())) return;
    // Consume before processing: recreation must never repeat a search.
    intent.setAction(null);
    if (voiceCommand != null) {
      GleoIntentAdapter.reply(this, false, inputOnly);
      return;
    }
    try {
      voiceInputOnly = inputOnly;
      voiceCommand = GleoIntentAdapter.parse(intent);
      voiceDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
      voiceHandler.postDelayed(voiceTimeout, 5000);
      android.util.Log.i("ArriveOnGleo", "COMMAND_RECEIVED");
    } catch (Exception invalid) {
      GleoIntentAdapter.reply(this, false, inputOnly);
      android.widget.Toast.makeText(this, R.string.gleo_invalid, android.widget.Toast.LENGTH_LONG)
          .show();
      android.util.Log.i("ArriveOnGleo", "INVALID_INPUT");
    }
  }

  private void finishVoice(boolean accepted) {
    if (voiceCommand == null) return;
    voiceCommand = null;
    voiceHandler.removeCallbacks(voiceTimeout);
    reportVoice(accepted, voiceInputOnly);
  }

  private void reportVoice(boolean accepted, boolean inputOnly) {
    GleoIntentAdapter.reply(this, accepted, inputOnly);
    android.widget.Toast.makeText(
            this,
            accepted
                ? (inputOnly ? R.string.gleo_input_applied : R.string.gleo_started)
                : R.string.gleo_blocked,
            android.widget.Toast.LENGTH_LONG)
        .show();
    android.util.Log.i(
        "ArriveOnGleo",
        accepted ? (inputOnly ? "PARTY_APPLIED" : "MOCK_SEARCH_STARTED") : "COMMAND_BLOCKED");
  }

  private void applyVoice() {
    if (voiceCommand == null || !voiceResumed) return;
    var reason = model.driving().reason();
    if (android.os.SystemClock.elapsedRealtime() >= voiceDeadline) {
      finishVoice(false);
      return;
    }
    if (reason == DrivingRestrictionController.Reason.CONNECTING
        || reason == DrivingRestrictionController.Reason.STOPPED
        || model.busy()) return;
    if (!model.canWrite() || (!voiceInputOnly && !model.claimVoice(voiceCommand))) {
      finishVoice(false);
      return;
    }
    var command = voiceCommand;
    // Clear before openDestination notifies observers reentrantly.
    boolean inputOnly = voiceInputOnly;
    voiceCommand = null;
    voiceHandler.removeCallbacks(voiceTimeout);
    model.openDestination();
    if (destinationPanel == null) {
      reportVoice(false, inputOnly);
    } else if (inputOnly) {
      reportVoice(destinationPanel.inputVoice(command), true);
    } else {
      reportVoice(destinationPanel.searchVoice(command), false);
    }
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
    LinearLayout row = new LinearLayout(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    int minimum = label == R.string.adults ? 1 : 0;
    int maximum = label == R.string.adults ? 8 : 6;
    Button minus = new Button(this);
    minus.setText(R.string.home_minus);
    minus.setContentDescription(getString(R.string.home_decrease, getString(label)));
    VehicleUi.button(minus, false);
    minus.setEnabled(model.canWrite());
    field.setGravity(android.view.Gravity.CENTER);
    Button plus = new Button(this);
    plus.setText(R.string.home_plus);
    plus.setContentDescription(getString(R.string.home_increase, getString(label)));
    VehicleUi.button(plus, false);
    plus.setEnabled(model.canWrite());
    minus.setOnClickListener(v -> adjustNumber(field, -1, minimum, maximum));
    plus.setOnClickListener(v -> adjustNumber(field, 1, minimum, maximum));
    row.addView(minus, new LinearLayout.LayoutParams(dp(80), dp(80)));
    row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(plus, new LinearLayout.LayoutParams(dp(80), dp(80)));
    content.addView(row);
    return field;
  }

  private void adjustNumber(EditText field, int delta, int minimum, int maximum) {
    if (!model.canWrite()) return;
    try {
      int value = Integer.parseInt(field.getText().toString());
      field.setText(
          String.format(
              java.util.Locale.ROOT, "%d", Math.max(minimum, Math.min(maximum, value + delta))));
    } catch (NumberFormatException invalid) {
      field.setError(getString(R.string.invalid_input));
    }
  }

  private void render() {
    if (voiceOpen && voiceResumed) {
      voiceOpen = false;
      GleoIntentAdapter.opened(this);
      android.util.Log.i("ArriveOnGleo", "APP_OPENED");
    }
    applyVoice();
    if (model.destinationOpen()) {
      if (destinationPanel == null) destinationPanel = new DestinationPanel(this, model);
      if (destinationPanel.view.getParent() == null) setContentView(destinationPanel.view);
      destinationPanel.update();
      return;
    }
    destinationPanel = null;
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
    LinearLayout page = content;
    text(getString(R.string.ui_brand), 32);
    text(getString(R.string.ui_mock), 20);
    LinearLayout columns = new LinearLayout(this);
    boolean wide =
        getResources().getConfiguration().screenWidthDp >= 1100
            && getResources().getConfiguration().fontScale <= 1.3f;
    columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    page.addView(columns, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout conditionPanel = new LinearLayout(this);
    conditionPanel.setOrientation(LinearLayout.VERTICAL);
    LinearLayout mainPanel = new LinearLayout(this);
    mainPanel.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams left =
        new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.42f : 0);
    left.setMargins(0, 0, wide ? dp(24) : 0, 0);
    columns.addView(conditionPanel, left);
    columns.addView(mainPanel, new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.58f : 0));
    content = conditionPanel;
    LinearLayout destinationCard = VehicleUi.card(this);
    content.addView(destinationCard);
    content = destinationCard;
    text(getString(R.string.home_destination_label), 20);
    text(getString(R.string.home_destination), 32);
    text(getString(R.string.home_eta_value), 44);
    text(getString(R.string.home_eta_unavailable), 20);
    JSONObject searchState = state.optJSONObject("search");
    JSONObject request = searchState == null ? null : searchState.optJSONObject("request");
    JSONObject navigation = request == null ? null : request.optJSONObject("navigation");
    String eta = navigation == null ? "" : navigation.optString("eta");
    text(
        eta.isEmpty()
            ? getString(R.string.ui_trip_pending)
            : getString(R.string.ui_trip_time, time(eta)),
        20);
    content = mainPanel;
    LinearLayout journey = VehicleUi.card(this);
    content.addView(journey);
    content = journey;
    text(getString(R.string.home_journey), 20);
    text(stageLabel(state));
    text(getString(R.string.home_journey_steps), 20);
    content = mainPanel;
    LinearLayout connection = VehicleUi.card(this);
    content.addView(connection);
    content = connection;
    text(
        getString(
            model.busy()
                ? R.string.home_connecting
                : model.failed()
                    ? R.string.home_recovery
                    : model.connected() ? R.string.home_ready : R.string.home_connect),
        26);
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
    if (model.failed() && !model.busy())
      text(
          "AUTH_CREATE_REQUIRED".equals(model.failureCode())
              ? R.string.auth_create_notice
              : R.string.auth_recovery_notice);
    if (!model.busy() && model.canCreateIdentity())
      button(
          R.string.auth_create,
          !model.busy() && model.restriction().allowsInput(),
          v -> model.run(MockRepository::createIdentity));
    else if (!model.busy() && (!model.connected() || model.failed())) {
      button(R.string.connect, !model.busy(), v -> model.run(MockRepository::connect));
    }
    if (model.connected() && !model.busy() && !model.failed() && model.restriction().allowsInput())
      text(getString(nextAction(state)), 20);
    content = mainPanel;
    LinearLayout progressCard = VehicleUi.card(this);
    content.addView(progressCard);
    content = progressCard;
    text(getString(R.string.home_phone), 24);
    JSONObject phone = state.optJSONObject("call");
    text(
        phone == null
            ? getString(R.string.home_phone_idle)
            : getString(
                R.string.home_phone_status,
                label(phone.optString("purpose")),
                label(phone.optString("status"))),
        20);
    text(getString(R.string.home_booking), 24);
    JSONObject booking = state.optJSONObject("reservation");
    text(
        booking == null
            ? getString(R.string.home_booking_idle)
            : getString(
                R.string.home_booking_status,
                getString(
                    switch (booking.optString("status")) {
                      case "CONFIRMED" -> R.string.status_confirmed;
                      case "PENDING" -> R.string.status_pending;
                      case "REJECTED" -> R.string.status_rejected;
                      default -> R.string.status_unknown;
                    })),
        20);
    content = mainPanel;
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
    boolean unknownTask =
        activeCall != null && "UNKNOWN".equals(activeCall.optString("status"))
            || reservationState != null && "UNKNOWN".equals(reservationState.optString("status"));
    if (unknownTask) text(R.string.unknown_read_only);
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
    if (!state.has("pending")
        && !state.has("searchId")
        && !state.has("reservationId")
        && !state.has("callId")
        && state.has("searchReconciliations")) {
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
      JSONArray rejected = state.optJSONArray("rejectedReservations");
      if (rejected != null && rejected.length() > 0) {
        JSONObject last = rejected.getJSONObject(rejected.length() - 1);
        text(
            getString(
                R.string.rebooking_history,
                rejected.length(),
                time(last.getString("originalRequestedTime"))),
            22);
      }
      if (state.has("reservation")) reservation(state.getJSONObject("reservation"));
      if (MockRepository.canPrepareNewBooking(state))
        button(
            R.string.rebooking_start,
            model.canWrite(),
            v ->
                confirm(
                    R.string.rebooking_title,
                    getString(R.string.rebooking_body),
                    MockRepository::prepareNewBooking));
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
        content = conditionPanel;
        LinearLayout conditionsCard = VehicleUi.card(this);
        content.addView(conditionsCard);
        content = conditionsCard;
        text(getString(R.string.conditions), 28);
        text(getString(R.string.home_conditions_hint), 20);
        adults = number(R.string.adults, adultsInput);
        children = number(R.string.children, childrenInput);
        parking = new CheckBox(this);
        parking.setText(R.string.parking);
        parking.setChecked(parkingInput);
        parking.setEnabled(model.canWrite());
        parking.setMinHeight(dp(72));
        VehicleUi.text(parking, 24);
        content.addView(parking);
        text(getString(R.string.home_pet), 24);
        text(getString(R.string.home_pet_unavailable), 20);
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
        content = mainPanel;
      }
      if (activeCall != null || state.has("reservation") || state.has("pending"))
        button(
            unknownTask ? R.string.unknown_refresh : R.string.refresh,
            !model.busy(),
            v -> model.run(MockRepository::refresh));
      if (state.has("search") && !state.has("reservationId")) candidates(state);
      if (!state.has("search") && !state.has("reservation")) {
        LinearLayout empty = VehicleUi.card(this);
        content.addView(empty);
        content = empty;
        text(getString(R.string.home_results_title), 28);
        text(getString(R.string.home_results_hint), 22);
        content = mainPanel;
      }
      button(R.string.preview_open, model.canWrite(), v -> model.openPreview());
      button(R.string.destination_open, model.canWrite(), v -> model.openDestination());
      content = page;
      diagnostics();

    } catch (Exception malformed) {
      text(R.string.failure);
    }
    scroll.post(() -> scroll.scrollTo(0, restoredY));
  }

  private int nextAction(JSONObject state) {
    if (state.has("pending")) return R.string.home_action_unknown;
    JSONObject call = state.optJSONObject("call");
    if (call != null) {
      if ("UNKNOWN".equals(call.optString("status"))) return R.string.home_action_unknown;
      if ("PENDING".equals(call.optString("status")))
        return "CHANGE".equals(call.optString("purpose"))
            ? R.string.home_action_change_wait
            : R.string.home_action_wait;
    }
    JSONObject reservation = state.optJSONObject("reservation");
    if (reservation != null) {
      if (!reservation.isNull("pendingChangeCallId")) return R.string.home_action_unknown;
      if ("CONFIRMED".equals(reservation.optString("status"))
          && call != null
          && "CHANGE".equals(call.optString("purpose"))) {
        if ("ACCEPTED".equals(call.optString("status")))
          return R.string.home_action_change_accepted;
        if ("REJECTED".equals(call.optString("status")))
          return R.string.home_action_change_rejected;
      }
      return switch (reservation.optString("status")) {
        case "CONFIRMED" -> R.string.home_action_confirmed;
        case "PENDING" -> R.string.home_action_wait;
        case "REJECTED" -> R.string.home_action_rejected;
        default -> R.string.home_action_unknown;
      };
    }
    if (call != null && "ANSWERED".equals(call.optString("status")))
      return R.string.home_action_answered;
    return state.has("searchId") ? R.string.home_action_candidates : R.string.home_ready_hint;
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
    boolean wide = false; // Dashboard already reserves a separate conditions column.
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
    Instant nextChange = MockRepository.nextChangeAt(value);
    if (allowed && nextChange != null && Instant.now().isBefore(nextChange)) {
      text(
          getString(
              R.string.change_cooldown,
              DateTimeFormatter.ofPattern("MM-dd HH:mm:ss 'KST'")
                  .withZone(ZoneId.of("Asia/Seoul"))
                  .format(nextChange)),
          22);
      allowed = false;
    }
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
