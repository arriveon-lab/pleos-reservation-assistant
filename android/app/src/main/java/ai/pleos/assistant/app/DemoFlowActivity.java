package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DrivingRestrictionController;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;

/**
 * Native UI design preview. All simulated transitions are explicitly operated from demo controls.
 */
public final class DemoFlowActivity extends ComponentActivity {
  private final DemoFlowState state = new DemoFlowState();
  private DrivingRestrictionController driving;
  private boolean allowed;
  private boolean debug;
  private boolean conversation;
  private LinearLayout body;
  private static final int[] NAMES = {
    R.string.flow_place_one, R.string.flow_place_two, R.string.flow_place_three
  };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) {
      state.stage = DemoFlowState.Stage.valueOf(saved.getString("stage", "CONDITIONS"));
      state.adults = saved.getInt("adults", 2);
      state.children = saved.getInt("children", 2);
      state.bufferMinutes = saved.getInt("buffer", 10);
      state.candidate = saved.getInt("candidate", 0);
      state.confirmedTime = saved.getString("confirmed", "");
      state.parking = saved.getBoolean("parking", true);
      state.highChair = saved.getBoolean("chair");
      state.pet = saved.getBoolean("pet");
      state.stepFree = saved.getBoolean("stepFree");
      state.changeAccepted = saved.getBoolean("accepted");
      state.changeRejected = saved.getBoolean("rejected");
    }
    driving =
        new DrivingRestrictionController(
            CarRestrictionSource.create(this),
            restriction -> {
              allowed = restriction.restriction().allowsInput();
              render();
            });
    render();
  }

  @Override
  protected void onStart() {
    super.onStart();
    driving.start();
  }

  @Override
  protected void onStop() {
    allowed = false;
    driving.stop();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("stage", state.stage.name());
    out.putInt("adults", state.adults);
    out.putInt("children", state.children);
    out.putInt("buffer", state.bufferMinutes);
    out.putInt("candidate", state.candidate);
    out.putString("confirmed", state.confirmedTime);
    out.putBoolean("parking", state.parking);
    out.putBoolean("chair", state.highChair);
    out.putBoolean("pet", state.pet);
    out.putBoolean("stepFree", state.stepFree);
    out.putBoolean("accepted", state.changeAccepted);
    out.putBoolean("rejected", state.changeRejected);
  }

  private void render() {
    LinearLayout page = column();
    page.setPadding(dp(24), dp(16), dp(24), dp(16));
    page.setBackgroundColor(getColor(R.color.ui_background));
    LinearLayout header = row();
    if (getResources().getConfiguration().screenWidthDp < 1100)
      header.setOrientation(LinearLayout.VERTICAL);
    page.addView(header);
    TextView brand = text(getString(R.string.flow_brand), 32);
    header.addView(
        brand,
        new LinearLayout.LayoutParams(
            header.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
            -2,
            header.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
    TextView badge = text(getString(R.string.flow_mode), 20);
    badge.setTextColor(getColor(R.color.ui_secondary));
    header.addView(badge);
    TextView steps = text(getString(R.string.flow_steps), 20);
    steps.setTextColor(getColor(R.color.ui_secondary));
    page.addView(steps);
    if (!allowed) page.addView(text(getString(R.string.flow_restricted), 24));
    ScrollView scroll = new ScrollView(this);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout columns = column();
    scroll.addView(columns);
    boolean wide = getResources().getConfiguration().screenWidthDp >= 1100;
    columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    LinearLayout trip = VehicleUi.card(this);
    LinearLayout.LayoutParams tripParams =
        new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.30f : 0);
    tripParams.setMargins(0, dp(8), wide ? dp(24) : 0, dp(8));
    columns.addView(trip, tripParams);
    trip.addView(text(getString(R.string.flow_destination_label), 20));
    trip.addView(text(getString(R.string.flow_destination), 36));
    trip.addView(text(getString(R.string.flow_remaining), 44));
    trip.addView(text(getString(R.string.flow_eta), 26));
    trip.addView(text(getString(R.string.flow_eta_source), 18));
    trip.addView(
        text(
            getString(
                R.string.flow_trip_summary, state.adults + state.children, state.requestedTime()),
            24));
    trip.addView(text(conditions(), 20));
    body = VehicleUi.card(this);
    columns.addView(body, new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 0.70f : 0));
    switch (state.stage) {
      case CONDITIONS -> conditionsScreen();
      case CANDIDATES -> candidates();
      case APPROVAL -> approval();
      case CALLING -> {
        title(R.string.flow_calling);
        details();
        label(R.string.flow_calling_hint, 24);
      }
      case CONFIRMED, CHANGED -> result();
      case CHANGE_REQUEST -> change();
      case CHANGE_WAIT -> {
        title(R.string.flow_change_wait);
        label(state.changeAccepted ? R.string.flow_accept_wait : R.string.flow_reject_wait, 24);
        label(getString(R.string.flow_original, state.confirmedTime), 32);
      }
      case UNKNOWN -> {
        title(R.string.flow_unknown);
        label(R.string.flow_unknown_hint, 24);
        if (!state.confirmedTime.isEmpty())
          label(getString(R.string.flow_original, state.confirmedTime), 32);
      }
      case DECLINED -> {
        title(R.string.flow_declined);
        label(R.string.flow_declined_hint, 24);
      }
    }
    if (state.stage.ordinal() >= DemoFlowState.Stage.CALLING.ordinal()) {
      button(
          body,
          R.string.flow_conversation,
          false,
          () -> {
            conversation = !conversation;
            render();
          });
      if (conversation) {
        label(R.string.flow_conversation_notice, 20);
        label(transcript(), 22);
      }
    }
    LinearLayout footer = row();
    page.addView(footer);
    Button dev =
        button(
            footer,
            R.string.flow_controls,
            false,
            () -> {
              debug = !debug;
              render();
            });
    dev.setEnabled(allowed);
    button(
        footer,
        R.string.flow_legacy,
        false,
        () -> startActivity(new Intent(this, ReservationActivity.class)));
    if (debug) {
      LinearLayout controls = column();
      page.addView(controls);
      controls.addView(text(getString(R.string.flow_controls_notice), 18));
      switch (state.stage) {
        case CALLING -> {
          button(
              controls,
              R.string.flow_sim_accept,
              false,
              () -> {
                state.accepted();
                render();
              });
          button(
              controls,
              R.string.flow_sim_unknown,
              false,
              () -> {
                state.unknown();
                render();
              });
        }
        case CONFIRMED ->
            button(
                controls,
                R.string.flow_sim_callback,
                false,
                () -> {
                  state.callback();
                  render();
                });
        case CHANGE_WAIT -> {
          button(
              controls,
              R.string.flow_sim_reconfirm,
              false,
              () -> {
                state.reconfirm();
                render();
              });
          button(
              controls,
              R.string.flow_sim_unknown,
              false,
              () -> {
                state.unknown();
                render();
              });
        }
        default -> controls.addView(text(getString(R.string.flow_controls_idle), 18));
      }
    }
    setContentView(page);
  }

  private void conditionsScreen() {
    title(R.string.flow_start);
    label(R.string.flow_start_hint, 22);
    counter(
        R.string.flow_adults,
        state.adults,
        () -> state.adults = Math.max(1, state.adults - 1),
        () -> state.adults = Math.min(8, state.adults + 1));
    counter(
        R.string.flow_children,
        state.children,
        () -> state.children = Math.max(0, state.children - 1),
        () -> state.children = Math.min(6, state.children + 1));
    counter(
        R.string.flow_buffer,
        state.bufferMinutes,
        () -> state.bufferMinutes = Math.max(0, state.bufferMinutes - 10),
        () -> state.bufferMinutes = Math.min(20, state.bufferMinutes + 10));
    label(getString(R.string.flow_booking_time, state.requestedTime()), 32);
    label(R.string.flow_conditions_label, 22);
    LinearLayout one = row();
    body.addView(one);
    toggle(one, R.string.flow_parking, state.parking, () -> state.parking = !state.parking);
    toggle(one, R.string.flow_chair, state.highChair, () -> state.highChair = !state.highChair);
    LinearLayout two = row();
    body.addView(two);
    toggle(two, R.string.flow_pet, state.pet, () -> state.pet = !state.pet);
    toggle(two, R.string.flow_stepfree, state.stepFree, () -> state.stepFree = !state.stepFree);
    button(
        body,
        R.string.flow_search,
        true,
        () -> {
          state.search();
          render();
        });
  }

  private void candidates() {
    title(R.string.flow_candidates);
    label(R.string.flow_candidates_hint, 22);
    for (int i = 0; i < NAMES.length; i++) {
      final int candidate = i;
      LinearLayout card = VehicleUi.card(this);
      body.addView(card);
      card.addView(text(getString(NAMES[i]), 28));
      card.addView(text(getString(R.string.flow_candidate_status), 20));
      button(
          card,
          R.string.flow_select,
          true,
          () -> {
            state.select(candidate);
            render();
          });
    }
    button(
        body,
        R.string.flow_edit,
        false,
        () -> {
          state.back();
          render();
        });
  }

  private void approval() {
    title(R.string.flow_approval);
    details();
    label(R.string.flow_reservation_name, 22);
    label(R.string.flow_approval_hint, 22);
    button(
        body,
        R.string.flow_approve,
        true,
        () -> {
          state.approve();
          debug = false;
          render();
        });
    button(
        body,
        R.string.flow_other,
        false,
        () -> {
          state.back();
          render();
        });
  }

  private void result() {
    title(
        state.stage == DemoFlowState.Stage.CHANGED
            ? R.string.flow_changed
            : R.string.flow_confirmed);
    label(getString(NAMES[state.candidate]), 30);
    label(getString(R.string.flow_result, state.confirmedTime, state.adults + state.children), 44);
    label(R.string.flow_no_deposit, 24);
    label(R.string.flow_eta, 24);
    if (state.changeRejected) label(R.string.flow_change_rejected, 24);
    label(R.string.flow_callback_hint, 22);
  }

  private void change() {
    title(R.string.flow_incoming);
    label(getString(NAMES[state.candidate]), 30);
    label(getString(R.string.flow_time_change, state.confirmedTime), 38);
    label(R.string.flow_change_reason, 24);
    label(R.string.flow_change_eta, 24);
    button(
        body,
        R.string.flow_accept_change,
        true,
        () -> {
          state.decide(true);
          debug = false;
          render();
        });
    button(
        body,
        R.string.flow_reject_change,
        false,
        () -> {
          state.decide(false);
          debug = false;
          render();
        });
    label(R.string.flow_not_cancel, 18);
  }

  private void details() {
    label(getString(NAMES[state.candidate]), 30);
    label(
        getString(R.string.flow_result, state.requestedTime(), state.adults + state.children), 36);
    label(conditions(), 22);
  }

  private String conditions() {
    java.util.List<String> values = new java.util.ArrayList<>();
    if (state.parking) values.add(getString(R.string.flow_parking));
    if (state.highChair) values.add(getString(R.string.flow_chair));
    if (state.pet) values.add(getString(R.string.flow_pet));
    if (state.stepFree) values.add(getString(R.string.flow_stepfree));
    return values.isEmpty() ? getString(R.string.flow_no_conditions) : String.join(" · ", values);
  }

  private String transcript() {
    if (state.confirmedTime.isEmpty()) return getString(R.string.flow_transcript_pending);
    if (state.stage == DemoFlowState.Stage.CHANGED)
      return getString(R.string.flow_transcript_changed);
    return getString(
        R.string.flow_transcript_confirmed, state.confirmedTime, state.adults + state.children);
  }

  private void counter(int name, int value, Runnable minus, Runnable plus) {
    LinearLayout line = row();
    body.addView(line);
    line.addView(text(getString(name) + "  " + value, 26), new LinearLayout.LayoutParams(0, -2, 1));
    Button down =
        button(
            line,
            R.string.home_minus,
            false,
            () -> {
              minus.run();
              render();
            });
    down.setContentDescription(getString(R.string.flow_less, getString(name)));
    Button up =
        button(
            line,
            R.string.home_plus,
            false,
            () -> {
              plus.run();
              render();
            });
    up.setContentDescription(getString(R.string.flow_more, getString(name)));
  }

  private void toggle(LinearLayout parent, int name, boolean selected, Runnable action) {
    Button view =
        button(
            parent,
            name,
            selected,
            () -> {
              action.run();
              render();
            });
    view.setContentDescription(
        getString(
            R.string.flow_toggle,
            getString(name),
            getString(selected ? R.string.flow_selected : R.string.flow_unselected)));
    view.setSelected(selected);
    view.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void title(int resource) {
    label(resource, 36);
  }

  private void label(int resource, int size) {
    label(getString(resource), size);
  }

  private void label(String value, int size) {
    body.addView(text(value, size));
  }

  private TextView text(String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    VehicleUi.text(v, size);
    return v;
  }

  private Button button(LinearLayout parent, int name, boolean primary, Runnable action) {
    Button v = new Button(this);
    v.setText(name);
    VehicleUi.button(v, primary);
    v.setEnabled(allowed);
    v.setOnClickListener(
        w -> {
          if (allowed) action.run();
        });
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(
            parent.getOrientation() == LinearLayout.HORIZONTAL ? -2 : -1, -2);
    p.setMargins(dp(4), dp(6), dp(4), dp(6));
    parent.addView(v, p);
    return v;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = column();
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private int dp(int n) {
    return VehicleUi.dp(this, n);
  }
}
