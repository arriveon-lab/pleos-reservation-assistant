package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DrivingRestrictionController;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stationary, separate phone-server session. This preparation build cannot dispatch calls. */
public final class PhoneTrialActivity extends ComponentActivity {
  private PhoneTrialViewModel model;
  private DrivingRestrictionController driving;
  private LinearLayout content;
  private AlertDialog approval;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (!BuildConfig.DEBUG) {
      finish();
      return;
    }
    model = new ViewModelProvider(this).get(PhoneTrialViewModel.class);
    driving =
        new DrivingRestrictionController(
            CarRestrictionSource.create(this),
            state -> {
              model.allowed = state.restriction().allowsInput();
              if (!model.allowed && approval != null) approval.dismiss();
              render();
            });
    render();
  }

  @Override
  protected void onStart() {
    super.onStart();
    model.observer = this::render;
    driving.start();
  }

  @Override
  protected void onStop() {
    model.allowed = false;
    model.observer = null;
    if (approval != null) approval.dismiss();
    driving.stop();
    super.onStop();
  }

  private void render() {
    ScrollView scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int pad = VehicleUi.dp(this, 24);
    content.setPadding(pad, pad, pad, pad);
    content.setBackgroundColor(getColor(R.color.ui_background));
    scroll.addView(content);
    setContentView(scroll);
    label(getString(R.string.trial_title), 32);
    label(getString(R.string.trial_notice), 22);
    button(R.string.trial_back, true, this::finish);
    if (!model.allowed) {
      label(getString(R.string.trial_restricted), 26);
      return;
    }
    if (model.busy) {
      label(getString(R.string.trial_busy), 26);
      return;
    }
    if (!model.error.isEmpty()) label(getString(R.string.trial_error, model.error), 22);
    if (model.error.equals("CALLBACK_TEST_ACTIVE")) label(getString(R.string.trial_active), 24);
    PhoneTrialRepository r = model.repository;
    if (r == null) return;
    if (!r.connected()) {
      label(getString(R.string.trial_login_hint), 22);
      EditText user = input(R.string.trial_username, "", InputType.TYPE_CLASS_TEXT);
      EditText password =
          input(
              R.string.trial_password,
              "",
              InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      button(
          R.string.trial_login,
          true,
          () -> {
            String u = user.getText().toString(), p = password.getText().toString();
            password.setText("");
            model.run(() -> r.login(u, p));
          });
      if (r.credentials()) button(R.string.trial_refresh, true, () -> model.run(r::refresh));
      return;
    }
    button(R.string.trial_refresh, true, () -> model.run(r::refresh));
    if (r.pending()) {
      label(getString(R.string.trial_pending), 24);
      button(R.string.trial_retry, true, () -> model.run(r::retry));
    }
    JSONObject registration = r.registration;
    if (registration.optBoolean("active")) {
      label(
          getString(
              R.string.trial_registered,
              registration.optString("phoneLast4"),
              registration.optString("expiresAt")),
          24);
    } else {
      label(getString(R.string.trial_register_hint), 22);
      EditText number = input(R.string.trial_number, "", InputType.TYPE_CLASS_PHONE);
      button(
          R.string.trial_register,
          !r.pending(),
          () -> {
            String phone = number.getText().toString().replaceAll("[ -]", "");
            number.setText("");
            model.run(() -> r.register(phone));
          });
    }
    JSONArray bookings = r.state.optJSONArray("bookings");
    if (bookings != null && bookings.length() > 0) {
      for (int i = 0; i < bookings.length(); i++) renderBooking(bookings.optJSONObject(i));
      return;
    }
    JSONArray restaurants = r.state.optJSONArray("testRestaurants");
    if (restaurants == null || restaurants.length() == 0) {
      label(getString(R.string.trial_no_restaurants), 24);
      return;
    }
    JSONArray trips = r.state.optJSONArray("trips");
    JSONObject trip =
        trips == null || trips.length() == 0 ? null : trips.optJSONObject(trips.length() - 1);
    if (trip == null) {
      label(getString(R.string.trial_conditions), 24);
      EditText party = input(R.string.trial_party, "2", InputType.TYPE_CLASS_NUMBER);
      button(
          R.string.trial_save_conditions,
          !r.pending(),
          () -> {
            String value = party.getText().toString();
            model.run(
                () -> {
                  int size = Integer.parseInt(value);
                  if (size < 1 || size > 30) throw new IllegalStateException("INVALID_PARTY");
                  r.action(
                      "create_trip",
                      new JSONObject()
                          .put(
                              "conditions",
                              new JSONObject()
                                  .put("partySize", size)
                                  .put("highChairCount", 0)
                                  .put("petAccompanied", "NO")
                                  .put("stepFree", false)
                                  .put("dietaryRequirements", "")));
                });
          });
      return;
    }
    label(getString(R.string.trial_choose), 26);
    EditText date =
        input(
            R.string.trial_date,
            LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
            InputType.TYPE_CLASS_DATETIME);
    EditText time = input(R.string.trial_time, "19:00", InputType.TYPE_CLASS_DATETIME);
    for (int i = 0; i < restaurants.length(); i++) {
      JSONObject restaurant = restaurants.optJSONObject(i);
      if (restaurant == null) continue;
      label(restaurant.optString("name"), 28);
      button(
          R.string.trial_create,
          registration.optBoolean("active") && !r.pending(),
          () -> {
            String day = date.getText().toString(), hour = time.getText().toString();
            model.run(
                () ->
                    r.action(
                        "create_booking",
                        new JSONObject()
                            .put("tripId", trip.getString("id"))
                            .put("tripVersion", trip.getInt("version"))
                            .put("restaurantId", restaurant.getString("id"))
                            .put("reservationName", getString(R.string.trial_fake_name))
                            .put("date", day)
                            .put("time", hour)));
          });
    }
  }

  private void renderBooking(JSONObject booking) {
    if (booking == null) return;
    label(booking.optString("restaurantName"), 30);
    String status = booking.optBoolean("reviewRequired") ? "UNKNOWN" : booking.optString("status");
    int description =
        switch (status) {
          case "AWAITING_APPROVAL" -> R.string.trial_awaiting;
          case "READY" -> R.string.trial_ready;
          case "CALLING" -> R.string.trial_calling;
          case "CONFIRMED" -> R.string.trial_confirmed;
          case "DECLINED" -> R.string.trial_declined;
          case "FAILED" -> R.string.trial_failed;
          default -> R.string.trial_unknown;
        };
    label(getString(description), 28);
    JSONObject conditions = booking.optJSONObject("snapshot");
    String summary =
        getString(
            R.string.trial_summary,
            booking.optString("date"),
            status.equals("CONFIRMED")
                ? booking.optString("confirmedTime")
                : booking.optString("requestedTime"),
            conditions == null ? 0 : conditions.optInt("partySize"));
    label(summary, 26);
    if (status.equals("AWAITING_APPROVAL")) {
      button(
          R.string.trial_approve,
          !model.repository.pending(),
          () -> {
            approval =
                new AlertDialog.Builder(this)
                    .setTitle(R.string.trial_approve)
                    .setMessage(
                        booking.optString("restaurantName")
                            + "\n"
                            + summary
                            + "\n"
                            + getString(R.string.trial_approve_notice))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        R.string.trial_approve,
                        (dialog, which) ->
                            model.run(
                                () ->
                                    model.repository.action(
                                        "approve_booking",
                                        new JSONObject()
                                            .put("bookingId", booking.getString("id"))
                                            .put("version", booking.getInt("version")))))
                    .create();
            approval.show();
            VehicleUi.button(approval.getButton(AlertDialog.BUTTON_POSITIVE), true);
            VehicleUi.button(approval.getButton(AlertDialog.BUTTON_NEGATIVE), false);
          });
    }
    if (status.equals("READY")) {
      if (!BuildConfig.PHONE_TRIAL_DIAL || model.repository.dialAttempted()) {
        label(getString(R.string.trial_dial_hold), 24);
        button(R.string.trial_dial, false, () -> {});
      } else {
        label(getString(R.string.trial_dial_cost), 24);
        EditText target = input(R.string.trial_number, "", InputType.TYPE_CLASS_PHONE);
        button(
            R.string.trial_dial_once,
            !model.repository.pending(),
            () -> {
              String phone = target.getText().toString().replaceAll("[ -]", "");
              if (!phone.matches("010[0-9]{8}")) {
                target.setError(getString(R.string.invalid_input));
                return;
              }
              approval =
                  new AlertDialog.Builder(this)
                      .setTitle(R.string.trial_dial_once)
                      .setMessage(
                          getString(R.string.trial_dial_confirm, phone.substring(7))
                              + "\n"
                              + summary)
                      .setNegativeButton(android.R.string.cancel, null)
                      .setPositiveButton(
                          R.string.trial_dial_once,
                          (d, w) ->
                              model.run(
                                  () ->
                                      model.repository.dial(
                                          booking.getString("id"),
                                          booking.getInt("version"),
                                          phone)))
                      .create();
              approval.show();
              VehicleUi.button(approval.getButton(AlertDialog.BUTTON_POSITIVE), true);
              VehicleUi.button(approval.getButton(AlertDialog.BUTTON_NEGATIVE), false);
            });
      }
    }
    JSONArray events = model.repository.state.optJSONArray("events");
    if (events != null)
      for (int i = 0; i < events.length(); i++) {
        JSONObject event = events.optJSONObject(i);
        if (event != null && booking.optString("id").equals(event.optString("bookingId")))
          label(event.optString("message"), 22);
      }
  }

  private void label(String value, int size) {
    TextView text = new TextView(this);
    text.setText(value);
    VehicleUi.text(text, size);
    content.addView(text);
  }

  private EditText input(int resource, String value, int type) {
    label(getString(resource), 22);
    EditText field = new EditText(this);
    field.setContentDescription(getString(resource));
    field.setInputType(type);
    field.setSaveEnabled(false);
    field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    field.setText(value);
    VehicleUi.input(field);
    content.addView(field);
    return field;
  }

  private void button(int resource, boolean enabled, Runnable action) {
    Button button = new Button(this);
    button.setText(resource);
    VehicleUi.button(button, false);
    button.setEnabled(enabled);
    button.setOnClickListener(v -> action.run());
    content.addView(button);
  }
}
