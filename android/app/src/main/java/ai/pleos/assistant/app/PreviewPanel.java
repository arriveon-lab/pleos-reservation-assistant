package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.PreviewController;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A separate, memory-only preview. Candidates never become booking or inquiry commands. */
final class PreviewPanel {
  final ScrollView view;
  private final ReservationActivity activity;
  private final ReservationViewModel model;
  private final LinearLayout form, results, conditions;
  private final List<View> inputs = new ArrayList<>();
  private final EditText adults, children, wait, radius, cuisines;
  private final CheckBox parking, childFriendly;
  private final Spinner destination;
  private final Button search, cancel, conditionsToggle, retryDriving;
  private final TextView restriction;
  private String rendered = "";
  private String lastState = "";

  PreviewPanel(ReservationActivity activity, ReservationViewModel model, Bundle draft) {
    this.activity = activity;
    this.model = model;
    view = new ScrollView(activity);
    form = new LinearLayout(activity);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(16), dp(24), dp(24));
    form.setBackgroundColor(activity.getColor(R.color.ui_background));
    view.addView(form);
    text(form, activity.getString(R.string.preview_title), 32);
    text(form, activity.getString(R.string.ui_mock), 22);
    text(form, activity.getString(R.string.ui_preview_hint), 22);
    text(form, activity.getString(R.string.ui_preview_eta), 22);
    button(form, R.string.preview_back, () -> model.closePreview());
    restriction = text(form, "", 22);
    retryDriving = button(form, R.string.driving_retry, activity::retryDriving);
    conditionsToggle = button(form, R.string.ui_preview_conditions_close, () -> toggleConditions());
    conditions = VehicleUi.card(activity);
    form.addView(conditions);
    text(conditions, activity.getString(R.string.preview_destination), 24);
    destination = new Spinner(activity);
    destination.setMinimumHeight(dp(72));
    destination.setContentDescription(activity.getString(R.string.preview_destination));
    ArrayAdapter<String> choices =
        new ArrayAdapter<>(
            activity,
            android.R.layout.simple_spinner_item,
            activity.getResources().getStringArray(R.array.preview_destinations)) {
          @Override
          public View getDropDownView(
              int position, View convertView, android.view.ViewGroup parent) {
            TextView row = (TextView) super.getDropDownView(position, convertView, parent);
            row.setTextSize(24);
            row.setMinimumHeight(dp(72));
            return row;
          }
        };
    choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    destination.setAdapter(choices);
    conditions.addView(destination);
    inputs.add(destination);
    adults = field(R.string.preview_adults, "2", true);
    children = field(R.string.preview_children, "1", true);
    wait = field(R.string.preview_wait, "15", true);
    radius = field(R.string.preview_radius, "1000", true);
    cuisines = field(R.string.preview_cuisines, "한식", false);
    parking = check(R.string.preview_parking, true);
    childFriendly = check(R.string.preview_children_feature, true);
    search = button(form, R.string.preview_search, this::search);
    cancel = button(form, R.string.preview_cancel, () -> model.invalidatePreview("CANCELLED"));
    results = new LinearLayout(activity);
    results.setOrientation(LinearLayout.VERTICAL);
    form.addView(results);
    for (EditText field : new EditText[] {adults, children, wait, radius, cuisines})
      field.addTextChangedListener(
          new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
              model.invalidatePreview("EDITING");
            }

            public void afterTextChanged(Editable value) {}
          });
    parking.setOnCheckedChangeListener((v, c) -> model.invalidatePreview("EDITING"));
    childFriendly.setOnCheckedChangeListener((v, c) -> model.invalidatePreview("EDITING"));
    destination.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          private int previous;

          public void onItemSelected(
              android.widget.AdapterView<?> p, View v, int position, long id) {
            if (v instanceof TextView label) label.setTextSize(24);
            if (previous != position) {
              previous = position;
              model.invalidatePreview("EDITING");
            }
          }

          public void onNothingSelected(android.widget.AdapterView<?> p) {
            model.invalidatePreview("EDITING");
          }
        });
    if (draft != null) {
      adults.setText(draft.getString("previewAdults", "2"));
      children.setText(draft.getString("previewChildren", "1"));
      wait.setText(draft.getString("previewWait", "15"));
      radius.setText(draft.getString("previewRadius", "1000"));
      cuisines.setText(draft.getString("previewCuisines", "한식"));
      parking.setChecked(draft.getBoolean("previewParking", true));
      childFriendly.setChecked(draft.getBoolean("previewChildFriendly", true));
      destination.setSelection(draft.getInt("previewDestination", 0));
    }
    if (BuildConfig.DEBUG) {
      Button toggle = button(form, R.string.ui_diagnostics, () -> {});
      LinearLayout diagnostics = VehicleUi.card(activity);
      form.addView(diagnostics);
      text(diagnostics, activity.getString(R.string.preview_disclosure), 22);
      button(diagnostics, R.string.driving_test_restricted, activity::debugRestriction);
      button(diagnostics, R.string.driving_test_failed, activity::debugFailure);
      VehicleUi.disclosure(
          toggle, diagnostics, R.string.ui_diagnostics, R.string.ui_diagnostics_close);
    }
    update();
  }

  void save(Bundle out) {
    out.putString("previewAdults", adults.getText().toString());
    out.putString("previewChildren", children.getText().toString());
    out.putString("previewWait", wait.getText().toString());
    out.putString("previewRadius", radius.getText().toString());
    out.putString("previewCuisines", cuisines.getText().toString());
    out.putBoolean("previewParking", parking.isChecked());
    out.putBoolean("previewChildFriendly", childFriendly.isChecked());
    out.putInt("previewDestination", destination.getSelectedItemPosition());
  }

  private void toggleConditions() {
    boolean show = conditions.getVisibility() != View.VISIBLE;
    conditions.setVisibility(show ? View.VISIBLE : View.GONE);
    conditionsToggle.setText(
        show ? R.string.ui_preview_conditions_close : R.string.ui_preview_conditions);
  }

  private int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView label = new TextView(activity);
    label.setText(value);
    VehicleUi.text(label, size);
    parent.addView(label);
    return label;
  }

  private Button button(LinearLayout parent, int resource, Runnable action) {
    Button b = new Button(activity);
    b.setText(resource);
    VehicleUi.button(b, resource == R.string.preview_search);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(4), 0, dp(4));
    parent.addView(b, params);
    return b;
  }

  private EditText field(int label, String initial, boolean numeric) {
    text(conditions, activity.getString(label), 24);
    EditText input = new EditText(activity);
    VehicleUi.input(input);
    input.setMinHeight(dp(72));
    input.setContentDescription(activity.getString(label));
    input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
    input.setText(initial);
    input.setMinimumHeight(dp(72));
    conditions.addView(input);
    inputs.add(input);
    return input;
  }

  private CheckBox check(int label, boolean checked) {
    CheckBox input = new CheckBox(activity);
    input.setText(label);
    VehicleUi.text(input, 24);
    input.setMinHeight(dp(72));
    input.setChecked(checked);
    conditions.addView(input);
    inputs.add(input);
    return input;
  }

  private void search() {
    try {
      model.searchPreview(
          new PreviewController.Input(
              Integer.parseInt(adults.getText().toString()),
              Integer.parseInt(children.getText().toString()),
              Integer.parseInt(wait.getText().toString()),
              parking.isChecked(),
              childFriendly.isChecked(),
              cuisines.getText().toString(),
              destination.getSelectedItemPosition(),
              Integer.parseInt(radius.getText().toString())));
    } catch (NumberFormatException invalid) {
      model.invalidatePreview("INVALID_INPUT");
    }
    ((android.view.inputmethod.InputMethodManager)
            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(view.getWindowToken(), 0);
    view.post(() -> view.smoothScrollTo(0, results.getTop()));
  }

  void update() {
    PreviewController.View state = model.previewView();
    boolean allowed = model.canWrite();
    inputs.forEach(v -> v.setEnabled(allowed));
    search.setEnabled(allowed && !"LOADING".equals(state.state()));
    cancel.setEnabled("LOADING".equals(state.state()) || state.result() != null);
    restriction.setText(activity.drivingLabel());
    restriction.setVisibility(model.restriction().allowsInput() ? View.GONE : View.VISIBLE);
    retryDriving.setVisibility(model.restriction().allowsInput() ? View.GONE : View.VISIBLE);
    String signature = state.toString();
    if (signature.equals(rendered)) return;
    rendered = signature;
    boolean completed = "LOADING".equals(lastState) && !"LOADING".equals(state.state());
    lastState = state.state();
    results.removeAllViews();
    if (completed && state.result() != null) {
      conditions.setVisibility(View.GONE);
      conditionsToggle.setText(R.string.ui_preview_conditions);
    }
    if (completed) view.post(() -> view.smoothScrollTo(0, results.getTop()));
    text(results, activity.getString(stateLabel(state.state())), 28);
    if ("LOADING".equals(state.state())) {
      ProgressBar progress = new ProgressBar(activity);
      progress.setContentDescription(activity.getString(R.string.preview_loading));
      results.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(40)));
    }
    if (state.cleanupPending())
      text(results, activity.getString(R.string.preview_cleanup_pending), 24);
    if (state.cleanupFailed())
      text(results, activity.getString(R.string.preview_cleanup_failed), 24);
    JSONObject value = state.result();
    if (value == null) return;
    try {
      JSONObject plan = value.optJSONObject("recommendationPlan");
      if (plan == null) {
        text(results, activity.getString(R.string.preview_no_plan), 24);
        return;
      }
      JSONArray candidates = plan.getJSONArray("candidates");
      LinearLayout row = new LinearLayout(activity);
      boolean wide = activity.getResources().getConfiguration().screenWidthDp >= 1100;
      row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      results.addView(row);
      for (int i = 0; i < candidates.length(); i++) {
        JSONObject c = candidates.getJSONObject(i);
        LinearLayout card = VehicleUi.card(activity);
        row.addView(card);
        if (wide) {
          LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
          layout.setMargins(dp(4), dp(8), dp(4), dp(8));
          card.setLayoutParams(layout);
        }
        text(
            card,
            activity.getString(
                R.string.preview_candidate, c.getInt("ordinal"), c.getString("name")),
            28);
        text(card, activity.getString(R.string.ui_review), 24);
        for (String summary : activity.getString(R.string.ui_preview_summary).split("\n"))
          text(card, summary, 24);
        Button expand = button(card, R.string.ui_preview_details, () -> {});
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        card.addView(details);
        VehicleUi.disclosure(
            expand, details, R.string.ui_preview_details, R.string.ui_preview_details_close);
        card = details;
        text(
            card,
            activity.getString(
                R.string.preview_times,
                displayTime(value.getString("fetchedAt")),
                displayTime(value.getString("expiresAt"))),
            22);
        text(
            card,
            activity.getString(
                R.string.preview_details,
                nullable(c, "cuisine"),
                nullable(c, "address"),
                c.getDouble("straightLineDistanceMeters")),
            24);
        text(card, activity.getString(R.string.preview_candidate_disclosure), 24);
        JSONArray reasons = c.getJSONArray("reasons");
        for (int n = 0; n < reasons.length(); n++)
          text(
              card,
              activity.getString(
                  "PREFERRED_CUISINE_MATCH".equals(reasons.getString(n))
                      ? R.string.preview_reason_cuisine
                      : R.string.preview_reason_range),
              24);
        JSONArray missing = c.getJSONArray("missingInformation");
        text(card, activity.getString(R.string.preview_missing_title), 24);
        if (missing.length() == 0)
          text(card, activity.getString(R.string.preview_missing_none), 24);
        for (int n = 0; n < missing.length(); n++)
          text(
              card,
              activity.getString(
                  switch (missing.getString(n)) {
                    case "ADDRESS" -> R.string.preview_missing_address;
                    case "CUISINE" -> R.string.preview_missing_cuisine;
                    case "PHONE" -> R.string.preview_missing_phone;
                    default -> R.string.preview_missing_unknown;
                  }),
              24);
        JSONArray questions = c.getJSONArray("questions");
        text(card, activity.getString(R.string.preview_question_title), 24);
        for (int n = 0; n < questions.length(); n++) {
          JSONObject q = questions.getJSONObject(n);
          text(card, activity.getString(R.string.preview_question, q.getString("prompt")), 24);
          if (q.getBoolean("arrivalTimeRequired"))
            text(card, activity.getString(R.string.preview_arrival_required), 24);
        }
      }
    } catch (Exception invalid) {
      results.removeAllViews();
      text(results, activity.getString(R.string.preview_invalid_response), 24);
    }
  }

  private String displayTime(String timestamp) {
    return java.time.format.DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss 'KST'", java.util.Locale.KOREA)
        .withZone(java.time.ZoneId.of("Asia/Seoul"))
        .format(java.time.Instant.parse(timestamp));
  }

  private String nullable(JSONObject v, String key) {
    return v.isNull(key) ? activity.getString(R.string.preview_unknown) : v.optString(key);
  }

  private int stateLabel(String state) {
    return switch (state) {
      case "IDLE" -> R.string.preview_idle;
      case "EDITING" -> R.string.preview_editing;
      case "LOADING" -> R.string.preview_loading;
      case "RESULTS" -> R.string.preview_results;
      case "EMPTY" -> R.string.preview_empty;
      case "FILTERED" -> R.string.preview_filtered;
      case "CANCELLED" -> R.string.preview_cancelled;
      case "CLOSED" -> R.string.preview_closed;
      case "EXPIRED", "CLOCK_UNCERTAIN" -> R.string.preview_expired;
      case "RESTRICTED" -> R.string.preview_restricted;
      case "INVALID_INPUT" -> R.string.preview_invalid_input;
      case "INVALID_RESPONSE" -> R.string.preview_invalid_response;
      case "AUTH_REQUIRED" -> R.string.preview_auth;
      case "UNAVAILABLE" -> R.string.preview_unavailable;
      case "RATE_LIMITED" -> R.string.preview_rate;
      case "REQUEST_CONFLICT" -> R.string.preview_conflict;
      case "FAILED", "NETWORK_FAILED" -> R.string.preview_failed;
      case "TIMED_OUT" -> R.string.preview_timeout;
      default -> R.string.preview_terminal;
    };
  }
}
