package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate MOCK-only destination UI; no candidate IDs enter the reservation workflow. */
final class DestinationPanel {
  final ScrollView view;
  private final ReservationActivity activity;
  private final ReservationViewModel model;
  private final LinearLayout root, results;
  private final Spinner destination;
  private final EditText adults, children;
  private final CheckBox highChair, stepFree;
  private final Button search;
  private final List<View> inputs = new ArrayList<>();
  private String rendered = "";

  DestinationPanel(ReservationActivity activity, ReservationViewModel model) {
    this.activity = activity;
    this.model = model;
    view = new ScrollView(activity);
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(24), dp(16), dp(24), dp(24));
    root.setBackgroundColor(activity.getColor(R.color.ui_background));
    view.addView(root);
    text(root, activity.getString(R.string.destination_title), 32);
    text(root, activity.getString(R.string.destination_notice), 22);
    button(R.string.preview_back, model::closeDestination);
    destination = new Spinner(activity);
    destination.setMinimumHeight(dp(72));
    destination.setContentDescription(activity.getString(R.string.preview_destination));
    ArrayAdapter<String> choices =
        new ArrayAdapter<>(
            activity,
            android.R.layout.simple_spinner_item,
            activity.getResources().getStringArray(R.array.destination_mock_places));
    choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    destination.setAdapter(choices);
    root.addView(destination);
    inputs.add(destination);
    adults = field(R.string.preview_adults, "2");
    children = field(R.string.preview_children, "1");
    text(root, activity.getString(R.string.destination_parking), 24);
    highChair = check(R.string.destination_high_chair);
    stepFree = check(R.string.destination_step_free);
    search =
        button(
            R.string.destination_search,
            () -> {
              try {
                model.searchDestination(
                    new DestinationController.Input(
                        destination.getSelectedItemPosition(),
                        Integer.parseInt(adults.getText().toString()),
                        Integer.parseInt(children.getText().toString()),
                        highChair.isChecked(),
                        stepFree.isChecked()));
              } catch (NumberFormatException invalid) {
                model.invalidateDestination("INVALID_INPUT");
              }
            });
    button(R.string.preview_cancel, () -> model.invalidateDestination("CANCELLED"));
    results = new LinearLayout(activity);
    results.setOrientation(LinearLayout.VERTICAL);
    root.addView(results);
    destination.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          private int previous;

          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
            if (position != previous) {
              previous = position;
              model.invalidateDestination("EDITING");
            }
          }
        });
  }

  private EditText field(int label, String initial) {
    text(root, activity.getString(label), 24);
    EditText input = new EditText(activity);
    input.setText(initial);
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setMinHeight(dp(72));
    VehicleUi.text(input, 24);
    root.addView(input);
    inputs.add(input);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            model.invalidateDestination("EDITING");
          }

          public void afterTextChanged(Editable value) {}
        });
    return input;
  }

  private CheckBox check(int label) {
    CheckBox box = new CheckBox(activity);
    box.setText(label);
    box.setMinHeight(dp(72));
    VehicleUi.text(box, 24);
    box.setOnCheckedChangeListener((v, checked) -> model.invalidateDestination("EDITING"));
    root.addView(box);
    inputs.add(box);
    return box;
  }

  void update() {
    DestinationController.View state = model.destinationView();
    boolean loading = java.util.Set.of("LOADING", "QUEUED", "RUNNING").contains(state.state());
    search.setEnabled(model.canWrite() && !loading);
    for (View v : inputs) v.setEnabled(model.canWrite());
    String signature =
        state.state() + state.candidates().toString() + state.cleanupFailed() + model.canWrite();
    if (signature.equals(rendered)) return;
    rendered = signature;
    results.removeAllViews();
    if (!model.canWrite()) text(results, activity.getString(R.string.destination_blocked), 24);
    int label =
        switch (state.state()) {
          case "LOADING", "QUEUED", "RUNNING" -> R.string.destination_loading;
          case "RESULTS" -> R.string.destination_results;
          case "EMPTY" -> R.string.destination_empty;
          case "FILTERED" -> R.string.destination_filtered;
          case "EXPIRED" -> R.string.destination_expired;
          case "NOT_FOUND" -> R.string.destination_not_found;
          case "FAILED" -> R.string.destination_failed;
          case "INVALID_INPUT" -> R.string.destination_invalid;
          case "APPROVAL_REQUIRED", "ALREADY_REQUESTED" -> R.string.destination_unavailable;
          case "CANCELLED", "CLOSED", "EDITING", "BLOCKED" -> R.string.destination_cleared;
          default -> R.string.destination_ready;
        };
    text(results, activity.getString(label), 26);
    if (state.cleanupFailed()) text(results, activity.getString(R.string.destination_cleanup), 22);
    try {
      for (int i = 0; i < state.candidates().length(); i++) {
        JSONObject candidate = state.candidates().getJSONObject(i);
        LinearLayout card = VehicleUi.card(activity);
        results.addView(card);
        text(card, candidate.getString("name"), 28);
        text(card, candidate.getString("address"), 22);
        text(card, activity.getString(R.string.destination_not_bookable), 22);
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        text(details, candidate.getString("identitySourceUrl"), 20);
        JSONArray assessments = candidate.getJSONArray("assessments");
        for (int j = 0; j < assessments.length(); j++) {
          JSONObject assessment = assessments.getJSONObject(j);
          int need =
              switch (assessment.getString("need")) {
                case "PARKING" -> R.string.feature_parking;
                case "HIGH_CHAIR" -> R.string.destination_high_chair;
                case "STEP_FREE_ACCESS" -> R.string.destination_step_free;
                case "CHILD_FRIENDLY" -> R.string.feature_children;
                default -> R.string.destination_other_condition;
              };
          int verdict =
              switch (assessment.getString("verdict")) {
                case "YES" -> R.string.destination_yes;
                case "NO" -> R.string.destination_no;
                case "CONFLICT" -> R.string.destination_conflict;
                default -> R.string.destination_unknown;
              };
          text(card, activity.getString(need) + " · " + activity.getString(verdict), 24);
          text(details, activity.getString(need) + " · " + activity.getString(verdict), 24);
          evidence(details, assessment.getJSONArray("evidence"));
        }
        JSONArray questions = candidate.getJSONArray("remainingQuestions");
        if (questions.length() > 0)
          text(card, activity.getString(R.string.destination_questions), 24);
        for (int q = 0; q < questions.length(); q++)
          text(card, needLabel(questions.getString(q)), 22);
        text(details, activity.getString(R.string.destination_reviews), 24);
        evidence(details, candidate.getJSONArray("reviews"));
        Button expand = new Button(activity);
        expand.setText(R.string.destination_details_show);
        expand.setMinHeight(dp(72));
        VehicleUi.text(expand, 24);
        expand.setOnClickListener(
            v -> {
              boolean show = details.getVisibility() == View.GONE;
              details.setVisibility(show ? View.VISIBLE : View.GONE);
              expand.setText(
                  show ? R.string.destination_details_hide : R.string.destination_details_show);
            });
        card.addView(expand);
        card.addView(details);
      }
    } catch (Exception invalid) {
      results.removeAllViews();
      text(results, activity.getString(R.string.destination_failed), 24);
    }
  }

  private String needLabel(String need) {
    return activity.getString(
        switch (need) {
          case "PARKING" -> R.string.feature_parking;
          case "HIGH_CHAIR" -> R.string.destination_high_chair;
          case "STEP_FREE_ACCESS" -> R.string.destination_step_free;
          case "CHILD_FRIENDLY" -> R.string.feature_children;
          default -> R.string.destination_other_condition;
        });
  }

  private void evidence(LinearLayout card, JSONArray list) throws Exception {
    for (int i = 0; i < list.length(); i++) {
      JSONObject e = list.getJSONObject(i);
      text(
          card,
          e.getString("title") + "\n" + e.getString("condition") + "\n" + e.getString("url"),
          20);
      text(
          card,
          activity.getString(
              R.string.destination_evidence_source,
              activity.getString(
                  switch (e.getString("sourceType")) {
                    case "MERCHANT_PAGE" -> R.string.destination_source_merchant;
                    case "VISITOR_REVIEW" -> R.string.destination_source_review;
                    default -> R.string.destination_source_other;
                  }),
              e.isNull("sourceUpdatedAt")
                  ? activity.getString(R.string.destination_unknown_time)
                  : e.getString("sourceUpdatedAt")),
          20);
      text(
          card,
          activity.getString(
              R.string.destination_evidence_time,
              e.getString("observedAt"),
              e.getString("validUntil")),
          20);
    }
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView v = new TextView(activity);
    v.setText(value);
    VehicleUi.text(v, size);
    parent.addView(v);
    return v;
  }

  private Button button(int label, Runnable action) {
    Button b = new Button(activity);
    b.setText(label);
    b.setMinHeight(dp(72));
    VehicleUi.text(b, 24);
    b.setOnClickListener(v -> action.run());
    root.addView(b);
    return b;
  }

  private int dp(int value) {
    return VehicleUi.dp(activity, value);
  }
}
