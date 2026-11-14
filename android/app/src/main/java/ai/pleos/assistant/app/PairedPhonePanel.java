package ai.pleos.assistant.app;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONObject;

/** Only included in the explicitly configured debug trial; uses the current app identity. */
final class PairedPhonePanel {
  final LinearLayout view;
  private final ReservationActivity activity;
  private final ReservationViewModel model;
  private AlertDialog approval;
  private String rendered;

  PairedPhonePanel(ReservationActivity activity, ReservationViewModel model) {
    this.activity = activity;
    this.model = model;
    view = VehicleUi.card(activity);
  }

  void update() {
    JSONObject binding = model.pairedStatus;
    boolean active = binding.optBoolean("active");
    boolean writable = model.canWrite();
    if (!writable && approval != null) approval.dismiss();
    String next =
        binding
            + "|"
            + model.pairingCode
            + "|"
            + model.pairedError
            + "|"
            + writable
            + "|"
            + model.pairedBusy;
    if (next.equals(rendered)) return;
    rendered = next;
    view.removeAllViews();
    text(view, R.string.paired_phone_title);
    if (active) {
      label(
          view,
          activity.getString(
              R.string.paired_phone_bound, binding.optString("phoneLast4"), mode(binding)));
      if ("LIVE".equals(binding.optString("mode"))) text(view, R.string.paired_phone_live_search);
      button(
          R.string.paired_phone_disconnect,
          writable,
          () -> model.pairedRequest("/disconnect", new JSONObject(), null, false));
    } else {
      text(view, R.string.paired_phone_swagger);
      if (!model.pairingCode.isEmpty())
        label(view, activity.getString(R.string.paired_phone_code, model.pairingCode));
      button(
          R.string.paired_phone_issue,
          writable,
          () -> model.pairedRequest("", new JSONObject(), null, false));
    }
    button(
        R.string.paired_phone_refresh, writable, () -> model.pairedRequest("", null, null, false));
    if (!model.pairedError.isEmpty()) {
      int message =
          switch (model.pairedError) {
            case "PAIRING_REQUIRED", "SEARCH_EXPIRED" -> R.string.paired_phone_expired;
            case "DUPLICATE_CANDIDATE", "ACTIVE_BOOKING_EXISTS", "CALL_ACTIVE" ->
                R.string.paired_phone_already;
            case "PAIRING_CALL_LIMIT", "PAIRING_SEARCH_LIMIT", "CALL_LIMIT" ->
                R.string.paired_phone_limit;
            case "PAIRING_RATE_LIMIT" -> R.string.paired_phone_wait;
            default -> R.string.paired_phone_error;
          };
      text(view, message);
    }
    if (model.pairedBusy) text(view, R.string.paired_phone_working);
    JSONObject result = binding.optJSONObject("latest");
    if (result != null) {
      text(view, R.string.paired_phone_result);
      int state =
          switch (result.optString("status")) {
            case "CONFIRMED" -> R.string.paired_phone_confirmed;
            case "DECLINED" -> R.string.paired_phone_declined;
            case "FAILED" -> R.string.paired_phone_failed;
            case "UNKNOWN" -> R.string.paired_phone_unknown;
            default -> R.string.paired_phone_pending;
          };
      label(
          view,
          activity.getString(
              R.string.paired_phone_result_line,
              mode(result),
              result.optString("restaurantName"),
              activity.getString(state)));
      label(
          view,
          activity.getString(
              R.string.paired_phone_details,
              result.optString("date"),
              result.optString("requestedTime"),
              result.optInt("partySize")));
      if (!result.optString("notice").isEmpty()) label(view, result.optString("notice"));
      JSONObject checks = result.optJSONObject("requirements");
      if (checks != null) {
        var keys = checks.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          int verdict =
              switch (checks.optString(key)) {
                case "CONFIRMED" -> R.string.paired_phone_verified;
                case "UNAVAILABLE", "NO" -> R.string.paired_phone_declined;
                default -> R.string.paired_phone_unknown;
              };
          label(
              view,
              activity.getString(
                  R.string.paired_phone_condition, key, activity.getString(verdict)));
        }
      }
    }
  }

  void select(String task, int ordinal, String candidate, int party, String conditions) {
    JSONObject binding = model.pairedStatus;
    if (!model.canWrite() || !binding.optBoolean("active") || !task.equals(model.pairedTask()))
      return;
    LinearLayout form = new LinearLayout(activity);
    form.setOrientation(LinearLayout.VERTICAL);
    int padding = VehicleUi.dp(activity, 24);
    form.setPadding(padding, 0, padding, 0);
    boolean live = "LIVE".equals(binding.optString("mode"));
    label(
        form,
        activity.getString(
            live ? R.string.paired_phone_live_consent : R.string.paired_phone_mock_consent,
            candidate,
            binding.optString("phoneLast4")));
    label(form, activity.getString(R.string.paired_phone_party, party, conditions));
    EditText name = input(form, R.string.paired_phone_name, "", 40, InputType.TYPE_CLASS_TEXT);
    EditText date =
        input(
            form,
            R.string.paired_phone_date,
            LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
            10,
            InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
    EditText time =
        input(
            form,
            R.string.paired_phone_time,
            "18:30",
            5,
            InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
    var scroll = new android.widget.ScrollView(activity);
    scroll.addView(form);
    approval =
        new AlertDialog.Builder(activity)
            .setTitle(R.string.paired_phone_approval)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                live ? R.string.paired_phone_dial : R.string.paired_phone_simulate, null)
            .create();
    approval.setOnShowListener(
        dialog ->
            approval
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String value = name.getText().toString().trim();
                      String day = date.getText().toString().trim();
                      String hour = time.getText().toString().trim();
                      if (value.isEmpty()) {
                        name.setError(activity.getString(R.string.paired_phone_input_error));
                        return;
                      }
                      try {
                        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")
                            || LocalDate.parse(day)
                                .isBefore(LocalDate.now(ZoneId.of("Asia/Seoul"))))
                          throw new IllegalArgumentException();
                      } catch (RuntimeException error) {
                        date.setError(activity.getString(R.string.paired_phone_input_error));
                        return;
                      }
                      if (!hour.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                        time.setError(activity.getString(R.string.paired_phone_input_error));
                        return;
                      }
                      if (model.pairedBusy) return;
                      model.approvePairedPhone(task, ordinal, day, hour, value);
                      approval.dismiss();
                      view.post(
                          () ->
                              view.requestRectangleOnScreen(
                                  new android.graphics.Rect(
                                      0, 0, view.getWidth(), view.getHeight()),
                                  false));
                    }));
    approval.show();
  }

  void dismiss() {
    if (approval != null) approval.dismiss();
  }

  private EditText input(LinearLayout parent, int field, String initial, int max, int type) {
    text(parent, field);
    EditText edit = new EditText(activity);
    edit.setHint(field);
    edit.setInputType(type);
    edit.setSingleLine(true);
    edit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
    edit.setText(initial);
    parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    return edit;
  }

  private String mode(JSONObject value) {
    return activity.getString(
        "LIVE".equals(value.optString("mode"))
            ? R.string.paired_phone_live
            : R.string.paired_phone_mock);
  }

  private void button(int title, boolean enabled, Runnable action) {
    Button button = new Button(activity);
    VehicleUi.button(button, false);
    button.setText(title);
    button.setEnabled(enabled);
    button.setOnClickListener(v -> action.run());
    view.addView(button, new LinearLayout.LayoutParams(-1, -2));
  }

  private void text(LinearLayout parent, int string) {
    label(parent, activity.getString(string));
  }

  private void label(LinearLayout parent, String value) {
    TextView text = new TextView(activity);
    VehicleUi.text(text, 22);
    text.setText(value);
    parent.addView(text);
  }
}
