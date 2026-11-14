package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import ai.pleos.assistant.ux.DrivingRestrictionController;
import ai.pleos.assistant.ux.MockRepository;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import java.time.*;
import org.json.*;

/** Native design with existing authenticated MOCK controllers and their durable journals. */
public final class FinalReservationActivity extends ComponentActivity {
  ReservationViewModel model;
  private DrivingRestrictionController driving;
  private NavigationDestinationAdapter navigation;
  private String selectedBinding = "";
  private android.app.AlertDialog demoChangeDialog;
  private String demoChangeId = "", demoChangeSummary = "";

  private void showDemoChangeAlert() {
    JSONObject result = model.pairedStatus.optJSONObject("latest");
    if (!ready() || !ai.pleos.assistant.ux.LiveTrialPolicy.confirmed(result)) return;
    String id = result.optString("id");
    String original = result.optString("confirmedTime");
    java.time.LocalTime proposed = java.time.LocalTime.parse(original).plusMinutes(20);
    String later = proposed.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    if (proposed.isBefore(java.time.LocalTime.parse(original)))
      later = getString(R.string.live_demo_next_day, later);
    String proposedLabel = later;
    tools = false;
    render();
    if (demoChangeDialog != null) demoChangeDialog.dismiss();
    demoChangeDialog =
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.live_demo_alert_title)
            .setMessage(
                getString(
                    R.string.live_demo_alert_body,
                    result.optString("restaurantName"),
                    original,
                    proposedLabel))
            .setPositiveButton(
                R.string.live_demo_accept,
                (dialog, which) -> finishDemoChange(id, proposedLabel, true))
            .setNegativeButton(
                R.string.live_demo_keep, (dialog, which) -> finishDemoChange(id, original, false))
            .setNeutralButton(R.string.live_demo_later, (dialog, which) -> {})
            .create();
    demoChangeDialog.setCanceledOnTouchOutside(false);
    demoChangeDialog.show();
    TextView message = demoChangeDialog.findViewById(android.R.id.message);
    if (message != null) message.setTextSize(26);
    for (int button : new int[] {-1, -2, -3}) {
      demoChangeDialog.getButton(button).setTextSize(22);
      demoChangeDialog.getButton(button).setMinHeight(dp(64));
    }
    demoChangeDialog
        .getWindow()
        .setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(900)), -2);
  }

  private void finishDemoChange(String id, String time, boolean accept) {
    JSONObject current = model.pairedStatus.optJSONObject("latest");
    if (!ready()
        || !ai.pleos.assistant.ux.LiveTrialPolicy.confirmed(current)
        || !id.equals(current.optString("id"))) return;
    demoChangeId = id;
    demoChangeSummary =
        getString(accept ? R.string.live_demo_accepted : R.string.live_demo_kept, time);
    render();
  }

  private boolean paired() {
    return BuildConfig.PAIRED_PHONE_DEMO;
  }

  private boolean hasBooking() {
    return paired()
        ? model.pairedAttempted()
            || (model.pairedStatus.optJSONObject("latest") != null
                && !"MOCK".equals(model.pairedStatus.optJSONObject("latest").optString("mode")))
        : model.phone.hasRequest();
  }

  private String task() {
    return paired() ? model.pairedTask() : model.phoneTask();
  }

  private String binding() {
    return model.pairedStatus.optString("phoneLast4")
        + ":"
        + model.pairedStatus.optString("mode")
        + ":"
        + model.pairedStatus.optString("expiresAt");
  }

  private boolean canSelect(int ordinal) {
    return paired()
        ? ready() && model.pairedLiveReady() && !hasBooking() && task() != null
        : model.phone.canApprove(task(), ordinal);
  }

  private int adults = 2, children = 2, buffer = 10, selected = -1;
  private boolean chair, stepFree, transcript, tools, restoring;
  private String stage = "CONDITIONS", selectedTask = "", selectedName = "", selectedAddress = "";
  private LinearLayout page, body, footer;
  private boolean wide;
  private final android.os.Handler voiceHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private ai.pleos.assistant.ux.GleoSearchCommand pendingVoice;
  private boolean voiceInputOnly, voiceResumed, voiceOpen;
  private long voiceDeadline;
  private final Runnable voiceTimeout = () -> finishVoice(false);

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    receiveVoice(intent);
    render();
  }

  private void receiveVoice(Intent intent) {
    String action = intent.getAction();
    if (action == null) return;
    if (action.startsWith(getPackageName() + "."))
      action = action.substring(getPackageName().length() + 1);
    if (GleoIntentAdapter.OPEN.equals(action)) {
      intent.setAction(null);
      voiceOpen = true;
      return;
    }
    boolean inputOnly = GleoIntentAdapter.SET_PARTY.equals(action);
    if (!inputOnly && !ai.pleos.assistant.ux.GleoSearchCommand.ACTION.equals(action)) return;
    intent.setAction(null);
    if (pendingVoice != null) {
      GleoIntentAdapter.reply(this, false, inputOnly);
      return;
    }
    try {
      var command = GleoIntentAdapter.parse(intent);
      if (command.adults() > 8 || command.children() > 6) throw new IllegalArgumentException();
      pendingVoice = command;
      voiceInputOnly = inputOnly;
      voiceDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
      voiceHandler.postDelayed(voiceTimeout, 5000);
      android.util.Log.i("ArriveOnGleo", "FINAL_COMMAND_RECEIVED");
    } catch (Exception invalid) {
      android.util.Log.w("ArriveOnGleo", "FINAL_INVALID_INPUT");
      GleoIntentAdapter.reply(this, false, inputOnly);
    }
  }

  private void finishVoice(boolean accepted) {
    if (pendingVoice == null) return;
    pendingVoice = null;
    voiceHandler.removeCallbacks(voiceTimeout);
    GleoIntentAdapter.reply(this, accepted, voiceInputOnly);
    android.util.Log.i(
        "ArriveOnGleo",
        accepted
            ? (voiceInputOnly ? "FINAL_PARTY_APPLIED" : "FINAL_MOCK_SEARCH_STARTED")
            : "FINAL_COMMAND_BLOCKED");
  }

  private void applyVoice() {
    if (!voiceResumed) return;
    if (voiceOpen) {
      voiceOpen = false;
      if (!hasBooking()) {
        stage = "CONDITIONS";
        tools = false;
      }
      GleoIntentAdapter.opened(this);
      android.util.Log.i("ArriveOnGleo", "FINAL_APP_OPENED");
      render();
    }
    if (pendingVoice == null) return;
    if (android.os.SystemClock.elapsedRealtime() >= voiceDeadline) {
      finishVoice(false);
      return;
    }
    if (model.busy() || model.driving().reason() == DrivingRestrictionController.Reason.CONNECTING)
      return;
    if (!ready()
        || hasBooking()
        || (!voiceInputOnly && paired())
        || (!voiceInputOnly && !model.claimVoice(pendingVoice))) {
      finishVoice(false);
      return;
    }
    adults = pendingVoice.adults();
    children = pendingVoice.children();
    boolean inputOnly = voiceInputOnly;
    finishVoice(true);
    tools = false;
    edit();
    if (!inputOnly) search();
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
    finishVoice(false);
    super.onPause();
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    model = new ViewModelProvider(this).get(ReservationViewModel.class);
    if (saved != null) {
      adults = saved.getInt("adults", 2);
      children = saved.getInt("children", 2);
      buffer = saved.getInt("buffer", 10);
      chair = saved.getBoolean("chair");
      stepFree = saved.getBoolean("stepFree");
    }
    if (saved == null) receiveVoice(getIntent());
    driving = new DrivingRestrictionController(CarRestrictionSource.create(this), model::driving);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (stage.equals("APPROVAL")) {
                  stage = "CANDIDATES";
                  render();
                } else if (!hasBooking()) {
                  edit();
                } else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                  setEnabled(true);
                }
              }
            });
  }

  @Override
  protected void onStart() {
    super.onStart();
    model.observe(this::render);
    driving.start();
    model.run(MockRepository::connect);
    if (paired()) {
      navigation = new NavigationDestinationAdapter(this, model::navigation);
      navigation.start();
    }
    render();
  }

  @Override
  protected void onStop() {
    model.observe(null);
    if (demoChangeDialog != null) demoChangeDialog.dismiss();
    if (navigation != null) {
      navigation.close();
      navigation = null;
    }
    model.invalidateDestination("CLOSED");
    selected = -1;
    selectedTask = "";
    driving.stop();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("adults", adults);
    out.putInt("children", children);
    out.putInt("buffer", buffer);
    out.putBoolean("chair", chair);
    out.putBoolean("stepFree", stepFree);
  }

  private boolean ready() {
    return model.canWrite()
        && (paired()
            || (BuildConfig.EC2_MOCK_DEMO
                && BuildConfig.MOCK_PHONE_DEMO
                && !BuildConfig.REAL_PLACE_DEMO));
  }

  private String time() {
    return String.format(java.util.Locale.ROOT, "19:%02d", buffer);
  }

  private String day() {
    return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();
  }

  private void edit() {
    model.invalidateDestination("EDITING");
    stage = "CONDITIONS";
    selected = -1;
    selectedTask = "";
    render();
  }

  private void search() {
    if (!ready() || hasBooking()) return;
    model.openDestination();
    if (paired()) {
      if (!model.pairedLiveReady() || !model.navigationReady()) return;
    } else {
      model.navigation(
          new DestinationController.Destination(
              getString(R.string.phone_mock_destination_name), 37.544, 127.055, false),
          "UNVERIFIED");
      model.confirmDemoNavigation();
    }
    stage = "CANDIDATES";
    model.searchDestination(
        new DestinationController.Input(
            model.navigationDestination(), adults, children, chair, stepFree));
    render();
  }

  private void select(int ordinal, String name, String address) {
    if (!ready() || !canSelect(ordinal)) return;
    selected = ordinal;
    selectedTask = task();
    selectedBinding = binding();
    selectedName = name;
    selectedAddress = address;
    stage = "APPROVAL";
    render();
  }

  private void approve() {
    if (!ready() || selected < 1 || !selectedTask.equals(task())) return;
    if (paired()) {
      if (!selectedBinding.equals(binding()) || hasBooking()) return;
      model.approvePairedPhone(
          selectedTask, selected, day(), time(), getString(R.string.design_test_name));
      render();
      return;
    }
    try {
      String at =
          LocalDate.parse(day())
              .atTime(19, buffer)
              .atZone(ZoneId.of("Asia/Seoul"))
              .toInstant()
              .toString();
      JSONObject request =
          new JSONObject()
              .put("approved", true)
              .put("candidateOrdinal", selected)
              .put("reservationAt", at)
              .put("reservationName", getString(R.string.design_test_name))
              .put("conditionScenario", "MET")
              .put("availabilityScenario", "AVAILABLE")
              .put("bookingScenario", "ACCEPT");
      model.approvePhone(selectedTask, request, adults + children);
      render();
    } catch (JSONException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private void render() {
    if (isFinishing()) return;
    if (!paired() && model.phone != null) model.phone.allow(ready());
    if (model.phone != null && hasBooking()) stage = "RESULT";
    if (stage.equals("APPROVAL") && (!ready() || !selectedTask.equals(task()))) {
      stage = "CANDIDATES";
      selected = -1;
    }
    wide =
        getResources().getConfiguration().screenWidthDp >= 1100
            && getResources().getConfiguration().fontScale <= 1.2;
    page = column();
    page.setPadding(dp(wide ? 32 : 24), dp(20), dp(wide ? 32 : 24), dp(20));
    page.setBackgroundColor(getColor(R.color.ui_background));
    LinearLayout header = row();
    page.addView(header);
    TextView brand = text(header, "ArriveOn", 30, true);
    brand.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    text(header, getString(paired() ? R.string.live_mode : R.string.design_mode), 18, false)
        .setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    action(
        header,
        "design_tools",
        R.string.design_tools,
        false,
        true,
        () -> {
          tools = !tools;
          render();
        });
    header
        .getChildAt(header.getChildCount() - 1)
        .setLayoutParams(new LinearLayout.LayoutParams(dp(160), -2));
    text(page, getString(R.string.design_steps), 20, false);
    if (!model.restriction().allowsInput())
      text(page, getString(R.string.flow_restricted), 22, true);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout columns = wide ? row() : column();
    columns.setGravity(Gravity.TOP);
    scroll.addView(columns);
    LinearLayout summary = card(columns);
    summary.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.27f : 0));
    text(
        summary,
        getString(paired() ? R.string.live_destination : R.string.design_destination),
        18,
        false);
    text(
        summary,
        paired()
            ? (model.navigationDestination() == null
                ? getString(R.string.live_no_destination)
                : model.navigationDestination().name())
            : getString(R.string.flow_destination),
        32,
        true);
    text(summary, getString(R.string.design_remaining), 18, false);
    text(summary, getString(R.string.design_remaining_value), 44, true);
    text(summary, getString(R.string.design_eta), 22, true);
    text(summary, getString(R.string.design_eta_notice), 18, false);
    if (!stage.equals("RESULT")) {
      text(summary, getString(R.string.design_request_time), 18, false);
      text(summary, time(), 38, true);
      text(
          summary,
          getString(R.string.design_party, adults + children, adults, children),
          22,
          false);
    }
    body = card(columns);
    LinearLayout.LayoutParams bp =
        new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.73f : 0);
    bp.setMargins(wide ? dp(24) : 0, wide ? 0 : dp(16), 0, 0);
    body.setLayoutParams(bp);
    body.setPadding(dp(wide ? 32 : 24), dp(24), dp(wide ? 32 : 24), dp(24));
    footer = row();
    page.addView(footer);
    if (tools && paired()) {
      action(
          body,
          "demo_change_alert",
          R.string.live_demo_trigger,
          true,
          ready()
              && ai.pleos.assistant.ux.LiveTrialPolicy.confirmed(
                  model.pairedStatus.optJSONObject("latest")),
          this::showDemoChangeAlert);
      text(body, getString(R.string.live_new_demo_notice), 20, false);
      action(
          body,
          "new_live_demo",
          R.string.live_new_demo,
          true,
          model.canStartNewPairedDemo(),
          () -> {
            if (model.startNewPairedDemo()) {
              adults = 2;
              children = 2;
              buffer = 10;
              chair = false;
              stepFree = false;
              tools = false;
              transcript = false;
              restoring = false;
              demoChangeId = "";
              demoChangeSummary = "";
              edit();
            }
          });
    }
    if (paired()) {
      if (model.connected()) pairedCallback();
      if (!model.connected() || model.snapshot().has("pending")) connection();
      else if (hasBooking()) pairedResult();
      else if (!model.pairedLiveReady()) pairing();
      else
        switch (stage) {
          case "CANDIDATES" -> candidates();
          case "APPROVAL" -> approval();
          default -> conditions();
        }
    } else if (!BuildConfig.EC2_MOCK_DEMO || BuildConfig.REAL_PLACE_DEMO) {
      title(R.string.design_mock_only);
      legacy();
    } else if (model.phone != null && hasBooking()) result();
    else if (!model.connected() || model.snapshot().has("pending")) connection();
    else
      switch (stage) {
        case "CANDIDATES" -> candidates();
        case "APPROVAL" -> approval();
        default -> conditions();
      }
    if (tools) {
      text(page, getString(R.string.design_change_off), 18, false);
      action(page, "change_accept", R.string.flow_accept_change, false, false, () -> {});
      action(page, "change_reject", R.string.flow_reject_change, false, false, () -> {});
      legacy();
    }
    setContentView(page);
    voiceHandler.post(this::applyVoice);
    if (paired() && ready() && !model.destinationOpen()) voiceHandler.post(model::openDestination);
    if (!paired() && model.connected() && model.phone != null && hasBooking() && !restoring) {
      restoring = true;
      model.phone.refresh();
    }
  }

  private void pairing() {
    title(R.string.live_pair_title);
    text(body, getString(R.string.live_pair_steps), 24, false);
    if (model.pairedStatus.optBoolean("active") && !model.pairedLiveReady()) {
      text(body, getString(R.string.live_mock_blocked), 28, true);
      action(
          body,
          "disconnect_mock",
          R.string.live_disconnect_mock,
          true,
          ready() && !model.pairedBusy,
          () -> model.pairedRequest("/disconnect", new JSONObject(), null, false));
    }
    if (!model.pairingCode.isEmpty()) text(body, model.pairingCode, 44, true);
    if (!model.pairedError.isEmpty())
      text(body, getString(R.string.live_error, model.pairedError), 22, false);
    action(
        body,
        "pair_code",
        R.string.live_pair_code,
        true,
        ready() && !model.pairedBusy && !model.pairedStatus.optBoolean("active"),
        () -> model.pairedRequest("", new JSONObject(), null, false));
    action(
        footer,
        "pair_refresh",
        R.string.live_pair_refresh,
        false,
        ready() && !model.pairedBusy,
        () -> model.pairedRequest("", null, null, false));
  }

  private void pairedCallback() {
    JSONObject booking = model.pairedStatus.optJSONObject("callback");
    JSONObject change = booking == null ? null : booking.optJSONObject("change");
    if (change == null) return;
    text(body, getString(R.string.live_callback_title), 30, true);
    text(body, booking.optString("restaurantName"), 24, true);
    detail(R.string.design_date, booking.optString("date"));
    detail(R.string.live_callback_original, change.optString("originalTime"));
    detail(R.string.live_callback_proposed, change.optString("proposedTime"));
    text(body, change.optString("reason"), 20, false);
    if (change.optInt("additionalCostKrw", -1) == 0)
      text(body, getString(R.string.live_callback_no_fee), 20, false);
    String state = change.optString("status");
    int status =
        switch (state) {
          case "AWAITING_USER" -> R.string.live_callback_waiting;
          case "RECONFIRMING" -> R.string.live_callback_reconfirming;
          case "APPLIED" -> R.string.live_callback_applied;
          case "REJECTED" -> R.string.live_callback_rejected;
          case "EXPIRED" -> R.string.live_callback_expired;
          default -> R.string.live_callback_unknown;
        };
    text(body, getString(status), 22, true);
    if (!"AWAITING_USER".equals(state)) return;
    if ("CHANGE_NOT_PENDING".equals(model.pairedError))
      text(body, getString(R.string.live_callback_refreshing), 20, false);
    boolean enabled =
        ready()
            && change.optBoolean("canDecide")
            && !model.pairedBusy
            && !"CHANGE_NOT_PENDING".equals(model.pairedError);
    String attemptId = booking.optString("id");
    String changeId = change.optString("id");
    int version = change.optInt("version");
    action(
        body,
        "live_callback_accept",
        R.string.live_callback_accept,
        false,
        enabled,
        () -> model.decidePairedChange(attemptId, changeId, version, true));
    action(
        body,
        "live_callback_reject",
        R.string.live_callback_reject,
        true,
        enabled,
        () -> model.decidePairedChange(attemptId, changeId, version, false));
  }

  private void pairedResult() {
    JSONObject r = model.pairedStatus.optJSONObject("latest");
    String status = r == null ? "UNKNOWN" : r.optString("status", "UNKNOWN");
    boolean confirmed = ai.pleos.assistant.ux.LiveTrialPolicy.confirmed(r);
    if (confirmed && demoChangeId.equals(r.optString("id")) && !demoChangeSummary.isEmpty()) {
      text(body, getString(R.string.live_demo_badge), 22, true);
      text(body, demoChangeSummary, 32, true);
      text(body, getString(R.string.live_demo_unchanged), 20, false);
    }
    title(confirmed ? R.string.live_confirmed : R.string.live_result_title);
    text(
        body,
        getString(
            R.string.live_result_mode, r == null ? "UNKNOWN" : r.optString("mode", "UNKNOWN")),
        24,
        true);
    text(body, getString(R.string.live_status, status), 28, true);
    if (r != null) {
      text(body, r.optString("restaurantName"), 30, true);
      detail(R.string.design_date, r.optString("date"));
      detail(R.string.design_request_time, r.optString("requestedTime"));
      if (confirmed) text(body, r.optString("confirmedTime"), 64, true);
      text(body, getString(R.string.design_total_people, r.optInt("partySize")), 26, true);
      text(body, r.optString("notice"), 22, false);
      JSONObject conditions = r.optJSONObject("requirements");
      if (conditions != null)
        for (var keys = conditions.keys(); keys.hasNext(); ) {
          String key = keys.next();
          text(body, key + " · " + conditions.optString(key), 22, false);
        }
    }

    if (!model.pairedError.isEmpty())
      text(body, getString(R.string.live_error, model.pairedError), 22, false);
    action(
        footer,
        "paired_refresh",
        R.string.live_pair_refresh,
        true,
        !model.pairedBusy,
        () -> model.pairedRequest("", null, null, false));
  }

  private void legacy() {
    action(
        page,
        "legacy",
        R.string.flow_legacy,
        false,
        true,
        () -> startActivity(new Intent(this, ReservationActivity.class)));
  }

  private void connection() {
    title(R.string.design_connect_title);
    text(body, getString(R.string.design_connect_notice), 22, false);
    if (model.snapshot().has("pending"))
      action(
          body,
          "recover",
          R.string.reconcile_search,
          true,
          !model.busy() && model.restriction().allowsInput(),
          () -> model.run(MockRepository::reconcileSearch));
    else if (model.canCreateIdentity())
      action(
          body,
          "connect",
          R.string.auth_create,
          true,
          !model.busy() && model.restriction().allowsInput(),
          () -> model.run(MockRepository::createIdentity));
    else
      action(
          body,
          "connect",
          R.string.design_connect,
          true,
          !model.busy(),
          () -> model.run(MockRepository::connect));
  }

  private void conditions() {
    title(R.string.design_conditions_title);
    if (!paired()) text(body, getString(R.string.design_voice_steps), 22, false);
    if (paired()) {
      text(
          body,
          getString(
              R.string.live_bound,
              model.pairedStatus.optString("phoneLast4"),
              model.pairedStatus.optString("mode")),
          22,
          true);
      text(body, getString(R.string.live_search_notice), 20, false);
      text(body, getString(R.string.live_destination_notice), 20, false);
      action(
          body,
          "confirm_destination",
          R.string.live_confirm_destination,
          false,
          ready() && model.navigationDestination() != null && !model.navigationReady(),
          model::confirmDemoNavigation);
    }
    text(
        body,
        getString(paired() ? R.string.live_search_hint : R.string.design_search_hint),
        22,
        false);
    counter(
        R.string.flow_adults,
        adults,
        1,
        8,
        () -> {
          adults--;
        },
        () -> {
          adults++;
        });
    counter(
        R.string.flow_children,
        children,
        0,
        6,
        () -> {
          children--;
        },
        () -> {
          children++;
        });
    counter(
        R.string.flow_buffer,
        buffer,
        0,
        20,
        () -> {
          buffer -= 10;
        },
        () -> {
          buffer += 10;
        });
    text(body, getString(R.string.design_time_formula, time()), 28, true);
    text(body, getString(R.string.flow_conditions_label), 22, true);
    action(body, "parking", R.string.design_parking, true, false, () -> {});
    toggle("chair", R.string.flow_chair, chair, () -> chair = !chair);
    action(body, "pet", R.string.design_pet, false, false, () -> {});
    toggle("stepfree", R.string.flow_stepfree, stepFree, () -> stepFree = !stepFree);
    text(body, getString(R.string.design_not_bookable), 18, false);
    action(
        footer,
        "search",
        R.string.design_search,
        true,
        ready() && (!paired() || (model.navigationReady() && model.pairedLiveReady())),
        this::search);
  }

  private void candidates() {
    title(R.string.flow_candidates);
    var view = model.destinationView();
    String status = view.state();
    if (!status.equals("RESULTS")) {
      int message =
          switch (status) {
            case "LOADING", "QUEUED", "RUNNING" -> R.string.design_loading;
            case "EMPTY", "FILTERED" -> R.string.design_empty;
            case "EXPIRED" -> R.string.design_expired;
            default -> R.string.design_search_error;
          };
      text(body, getString(message), 26, false);
    } else {
      text(
          body,
          getString(
              paired() ? R.string.live_candidates : R.string.design_candidate_count,
              view.candidates().length()),
          20,
          false);
      // Stack cards at narrow widths; equal columns in the vehicle's wide window.
      LinearLayout cards = wide ? row() : column();
      body.addView(cards);
      for (int i = 0; i < view.candidates().length(); i++) {
        JSONObject c = view.candidates().optJSONObject(i);
        if (c == null) continue;
        int ordinal = c.optInt("ordinal", i + 1);
        String name = c.optString("name"), address = c.optString("address");
        LinearLayout card = card(cards);
        LinearLayout.LayoutParams cp =
            new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        cp.setMargins(dp(4), dp(8), dp(4), dp(8));
        card.setLayoutParams(cp);
        text(card, name, 28, true);
        text(card, address, 18, false);
        text(card, getString(R.string.design_candidate_unknown), 20, false);
        action(
            card,
            "select_" + ordinal,
            R.string.flow_select,
            false,
            ready() && canSelect(ordinal),
            () -> select(ordinal, name, address));
      }
    }
    action(footer, "edit", R.string.flow_edit, false, ready(), this::edit);
  }

  private void approval() {
    title(R.string.design_approval_title);
    text(
        body,
        getString(paired() ? R.string.live_call_notice : R.string.design_mock_call_hint),
        22,
        false);
    if (paired())
      text(
          body,
          getString(
              R.string.live_bound,
              model.pairedStatus.optString("phoneLast4"),
              model.pairedStatus.optString("mode")),
          24,
          true);
    detail(R.string.design_restaurant, selectedName + "\n" + selectedAddress);
    detail(R.string.design_date, day());
    detail(R.string.design_request_time, time());
    detail(
        R.string.design_people,
        getString(R.string.design_party, adults + children, adults, children));
    detail(
        R.string.design_needs,
        getString(R.string.flow_parking)
            + (chair ? " · " + getString(R.string.flow_chair) : "")
            + (stepFree ? " · " + getString(R.string.flow_stepfree) : ""));
    detail(R.string.design_name, getString(R.string.design_test_name));
    text(body, getString(R.string.design_fee_notice), 20, false);
    action(
        footer,
        "other",
        R.string.flow_other,
        false,
        ready(),
        () -> {
          stage = "CANDIDATES";
          render();
        });
    action(
        footer,
        "approve",
        paired() ? R.string.live_approve : R.string.design_approve,
        true,
        ready() && selectedTask.equals(task()),
        this::approve);
  }

  private void result() {
    JSONObject r = model.phone.receipt();
    String status = model.phone.state();
    boolean confirmed = status.equals("CONFIRMED"), busy = model.phone.busy();
    title(
        busy
            ? R.string.design_checking
            : confirmed
                ? R.string.design_confirmed
                : status.equals("DECLINED") ? R.string.flow_declined : R.string.flow_unknown);
    text(body, r.optString("restaurantName", getString(R.string.design_saved)), 30, true);
    if (confirmed) {
      text(body, r.optString("confirmedTime"), 64, true);
      text(
          body,
          r.optString("date")
              + " · "
              + getString(R.string.design_total_people, r.optInt("partySize")),
          26,
          true);
    } else text(body, getString(R.string.design_no_retry), 22, false);
    if (r.has("requestedTime")) detail(R.string.design_request_time, r.optString("requestedTime"));
    detail(R.string.design_deposit, getString(R.string.design_unverified));
    JSONObject checks = r.optJSONObject("requirementChecks");
    if (checks != null) {
      var keys = checks.keys();
      while (keys.hasNext()) {
        String k = keys.next();
        String value = checks.optString(k);
        text(
            body,
            k
                + " · "
                + getString(
                    value.equals("CONFIRMED")
                        ? R.string.design_condition_ok
                        : value.equals("UNMET")
                            ? R.string.design_condition_no
                            : R.string.design_unverified),
            20,
            false);
      }
    }
    action(
        body,
        "conversation",
        transcript ? R.string.phone_conversation_hide : R.string.phone_conversation_show,
        false,
        true,
        () -> {
          transcript = !transcript;
          render();
        });
    if (transcript) {
      text(body, getString(R.string.design_conversation_notice), 18, false);
      JSONArray turns = r.optJSONArray("conversation");
      if (turns != null)
        for (int i = 0; i < turns.length(); i++) {
          JSONObject t = turns.optJSONObject(i);
          if (t == null) continue;
          LinearLayout bubble = card(body);
          text(
              bubble,
              getString(
                  t.optString("speaker").equals("agent")
                      ? R.string.design_agent
                      : R.string.design_restaurant),
              18,
              true);
          text(bubble, t.optString("message"), 22, false);
        }
    }
    if (model.snapshot().has("pending")) {
      action(
          body,
          "reconcile",
          R.string.reconcile_search,
          false,
          !model.busy() && model.restriction().allowsInput(),
          () -> model.run(MockRepository::reconcileSearch));
    }
    if (!model.connected()) {
      text(body, getString(R.string.design_connect_notice), 20, false);
      action(
          body,
          "reconnect",
          R.string.design_connect,
          false,
          !model.busy(),
          () -> model.run(MockRepository::connect));
    }
    text(body, getString(R.string.design_change_off), 18, false);
    action(footer, "refresh", R.string.phone_refresh, false, !busy, () -> model.phone.refresh());
    action(
        footer,
        "new_booking",
        R.string.design_new_booking,
        true,
        ready() && model.phone.canStartNew(),
        () -> {
          if (model.phone.startNew()) {
            adults = 2;
            children = 2;
            buffer = 10;
            chair = false;
            stepFree = false;
            tools = false;
            transcript = false;
            restoring = false;
            edit();
          }
        });
  }

  private void detail(int name, String value) {
    LinearLayout line = column();
    body.addView(line);
    text(line, getString(name), 18, false);
    text(line, value, 26, true);
  }

  private void title(int id) {
    text(body, getString(id), wide ? 34 : 30, true);
  }

  private void counter(int id, int value, int min, int max, Runnable minus, Runnable plus) {
    LinearLayout line = row();
    body.addView(line);
    TextView label = text(line, getString(id) + "  " + value, 26, true);
    label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    action(
        line,
        "minus_" + id,
        R.string.home_minus,
        false,
        ready() && value > min,
        () -> {
          minus.run();
          render();
        });
    action(
        line,
        "plus_" + id,
        R.string.home_plus,
        false,
        ready() && value < max,
        () -> {
          plus.run();
          render();
        });
    for (int i = 1; i < line.getChildCount(); i++) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(80));
      lp.setMargins(dp(8), dp(8), dp(8), dp(8));
      line.getChildAt(i).setLayoutParams(lp);
    }
  }

  private void toggle(String tag, int name, boolean checked, Runnable update) {
    Button b =
        action(
            body,
            tag,
            name,
            checked,
            ready(),
            () -> {
              update.run();
              render();
            });
    b.setSelected(checked);
    b.setContentDescription(
        getString(name)
            + " · "
            + getString(checked ? R.string.flow_selected : R.string.flow_unselected));
  }

  private Button action(
      LinearLayout parent, String tag, int title, boolean primary, boolean enabled, Runnable run) {
    Button b = new Button(this);
    b.setTag(tag);
    b.setText(title);
    VehicleUi.button(b, primary);
    b.setEnabled(enabled);
    b.setOnClickListener(
        v -> {
          if (b.isEnabled()) run.run();
        });
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
            -2,
            parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
    lp.setMargins(dp(8), dp(8), dp(8), dp(8));
    parent.addView(b, lp);
    return b;
  }

  private TextView text(LinearLayout parent, String value, int size, boolean bold) {
    TextView t = new TextView(this);
    VehicleUi.text(t, size);
    if (bold) t.setTypeface(null, Typeface.BOLD);
    t.setText(value);
    t.setLineSpacing(0, 1.15f);
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    return t;
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = VehicleUi.card(this);
    parent.addView(c);
    return c;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private int dp(int n) {
    return VehicleUi.dp(this, n);
  }
}
