package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DestinationController;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Large explicit choices, comparison first, and separate evidence; transient MOCK only. */
final class DestinationPanel {
  final ScrollView view;
  private final ReservationActivity activity;
  private final ReservationViewModel model;
  private final LinearLayout root, form, results;
  private final Button highChair, stepFree;
  private final Button search, navigationRetry, demoConfirm;
  private final List<View> inputs = new ArrayList<>();
  private final TextView navigationLabel;
  private final MockPhonePanel phonePanel;
  private final boolean wide;
  private int adultCount = 2, childCount = 1, detail = -1;
  private String rendered = "";
  private TextView adultValue, childValue;

  DestinationPanel(ReservationActivity activity, ReservationViewModel model) {
    this.activity = activity;
    this.model = model;
    wide =
        activity.getResources().getConfiguration().screenWidthDp >= 1100
            && activity.getResources().getConfiguration().fontScale <= 1.2f;
    view = new ScrollView(activity);
    view.setFillViewport(true);
    root = column();
    root.setPadding(dp(24), dp(16), dp(24), dp(24));
    root.setBackgroundColor(activity.getColor(R.color.ui_background));
    view.addView(root);
    LinearLayout header = row();
    root.addView(header);
    TextView title = text(header, activity.getString(R.string.destination_title), 32);
    title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    button(header, R.string.destination_exit, false, model::closeDestination);
    text(
        root,
        activity.getString(
            BuildConfig.REAL_PLACE_DEMO
                ? R.string.real_place_notice
                : R.string.destination_short_notice),
        22);
    form = column();
    root.addView(form);
    LinearLayout conditions = wide ? row() : column();
    form.addView(conditions);
    LinearLayout people = VehicleUi.card(activity);
    addCard(conditions, people, wide);
    text(people, activity.getString(R.string.destination_where), 26);
    navigationLabel = text(people, activity.getString(R.string.navigation_missing), 24);
    navigationRetry = button(people, R.string.navigation_retry, false, activity::retryNavigation);
    demoConfirm =
        button(people, R.string.navigation_demo_confirm, false, model::confirmDemoNavigation);
    demoConfirm.setVisibility(BuildConfig.NAVIGATION_DEMO ? View.VISIBLE : View.GONE);
    if (BuildConfig.NAVIGATION_DEMO)
      text(
          people,
          activity.getString(
              BuildConfig.REAL_PLACE_DEMO
                  ? R.string.real_place_navigation_notice
                  : R.string.navigation_demo_notice),
          20);
    stepper(people, true);
    stepper(people, false);
    LinearLayout needs = VehicleUi.card(activity);
    addCard(conditions, needs, wide);
    text(needs, activity.getString(R.string.destination_needs), 26);
    text(needs, activity.getString(R.string.destination_parking_fixed), 24);
    highChair = toggle(needs, R.string.destination_high_chair);
    stepFree = toggle(needs, R.string.destination_step_free);
    text(needs, activity.getString(R.string.destination_wait_fixed), 22);
    search =
        button(
            form,
            R.string.destination_search,
            true,
            () ->
                model.searchDestination(
                    new DestinationController.Input(
                        model.navigationDestination(),
                        adultCount,
                        childCount,
                        highChair.isSelected(),
                        stepFree.isSelected())));
    button(form, R.string.preview_cancel, false, () -> model.invalidateDestination("CANCELLED"));
    results = column();
    root.addView(results);
    phonePanel = new MockPhonePanel(activity, model);
    root.addView(phonePanel.view, 2);
  }

  boolean inputVoice(ai.pleos.assistant.ux.GleoSearchCommand command) {
    if (!model.canWrite()) return false;
    adultCount = command.adults();
    childCount = command.children();
    adultValue.setText(Integer.toString(adultCount));
    childValue.setText(Integer.toString(childCount));
    edit();
    return true;
  }

  boolean searchVoice(ai.pleos.assistant.ux.GleoSearchCommand command) {
    if (!model.canWrite() || !model.navigationReady()) return false;
    adultCount = command.adults();
    childCount = command.children();
    adultValue.setText(Integer.toString(adultCount));
    childValue.setText(Integer.toString(childCount));
    detail = -1;
    model.searchDestination(
        new DestinationController.Input(
            model.navigationDestination(),
            adultCount,
            childCount,
            highChair.isSelected(),
            stepFree.isSelected()));
    return true;
  }

  private void edit() {
    detail = -1;
    model.invalidateDestination("EDITING");
  }

  private void stepper(LinearLayout parent, boolean adult) {
    LinearLayout r = row();
    parent.addView(r);
    TextView label =
        text(
            r,
            activity.getString(adult ? R.string.destination_adults : R.string.destination_children),
            24);
    label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    TextView count = new TextView(activity);
    if (adult) adultValue = count;
    else childValue = count;
    VehicleUi.text(count, 28);
    count.setGravity(android.view.Gravity.CENTER);
    count.setMinWidth(dp(72));
    count.setText(Integer.toString(adult ? adultCount : childCount));
    Button less =
        button(
            r,
            R.string.destination_minus,
            false,
            () -> {
              if (adult) adultCount = Math.max(1, adultCount - 1);
              else childCount = Math.max(0, childCount - 1);
              count.setText(Integer.toString(adult ? adultCount : childCount));
              edit();
            });
    less.setContentDescription(
        activity.getString(
            adult ? R.string.destination_adults_less : R.string.destination_children_less));
    r.addView(count);
    Button more =
        button(
            r,
            R.string.destination_plus,
            false,
            () -> {
              if (adult) adultCount = Math.min(20, adultCount + 1);
              else childCount = Math.min(10, childCount + 1);
              count.setText(Integer.toString(adult ? adultCount : childCount));
              edit();
            });
    more.setContentDescription(
        activity.getString(
            adult ? R.string.destination_adults_more : R.string.destination_children_more));
    inputs.add(less);
    inputs.add(more);
  }

  private Button toggle(LinearLayout parent, int label) {
    Button b = new Button(activity);
    b.setText(label);

    style(b, false);
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    inputs.add(b);
    b.setOnClickListener(
        v -> {
          boolean checked = !b.isSelected();
          b.setSelected(checked);
          style(b, checked);
          edit();
        });
    return b;
  }

  void update() {
    phonePanel.update();
    if (BuildConfig.MOCK_PHONE_DEMO
        && model.phone != null
        && model.phone.hasRequest()
        && !model.phone.state().equals("DECLINED")) {
      form.setVisibility(View.GONE);
      results.setVisibility(View.GONE);
      return;
    }
    results.setVisibility(View.VISIBLE);
    DestinationController.View state = model.destinationView();
    boolean loading = java.util.Set.of("LOADING", "QUEUED", "RUNNING").contains(state.state());
    search.setEnabled(model.canWrite() && model.navigationReady() && !loading);
    boolean navigationLoading =
        java.util.Set.of("CONNECTING", "READING").contains(model.navigationState());
    navigationRetry.setEnabled(model.restriction().allowsInput() && !navigationLoading);
    var navigation = model.navigationDestination();
    demoConfirm.setEnabled(
        BuildConfig.NAVIGATION_DEMO
            && model.canWrite()
            && navigation != null
            && navigation.validFields()
            && model.navigationState().equals("UNVERIFIED"));
    navigationLabel.setText(
        navigation == null
            ? activity.getString(
                switch (model.navigationState()) {
                  case "CONNECTING", "READING" -> R.string.navigation_loading;
                  case "TIMEOUT" -> R.string.navigation_timeout;
                  case "PERMISSION_DENIED" -> R.string.navigation_permission;
                  default -> R.string.navigation_missing;
                })
            : activity.getString(
                navigation.demoConfirmed()
                    ? R.string.navigation_demo_ready
                    : model.navigationReady()
                        ? R.string.navigation_ready
                        : R.string.navigation_unverified,
                navigation.name()));
    search.setText(loading ? R.string.destination_searching : R.string.destination_search);
    for (View v : inputs) {
      v.setEnabled(model.canWrite());
      if (v instanceof Button b) style(b, b.isSelected());
    }
    style(search, true);
    boolean hasResults = "RESULTS".equals(state.state()) && state.candidates().length() > 0;
    form.setVisibility(hasResults ? View.GONE : View.VISIBLE);
    if (!hasResults) detail = -1;
    String signature =
        state.state()
            + state.candidates()
            + state.cleanupFailed()
            + model.canWrite()
            + detail
            + (model.phone == null ? "" : model.phone.state() + model.phone.busy());
    if (signature.equals(rendered)) return;
    rendered = signature;
    results.removeAllViews();
    if (!model.canWrite()) text(results, activity.getString(R.string.destination_blocked), 24);
    if (state.cleanupFailed()) text(results, activity.getString(R.string.destination_cleanup), 22);
    if (hasResults) {
      text(
          results,
          activity.getString(R.string.destination_comparison, state.candidates().length()),
          30);
      text(
          results,
          activity.getString(
              R.string.destination_selection_summary,
              model.navigationDestination().name(),
              adultCount,
              childCount),
          22);
      text(results, activity.getString(R.string.destination_not_bookable), 22);
      LinearLayout actions = row();
      results.addView(actions);
      Button change =
          button(
              actions,
              R.string.destination_change,
              false,
              () -> {
                edit();
                view.smoothScrollTo(0, 0);
              });
      change.setEnabled(model.canWrite());
      button(
          actions, R.string.preview_cancel, false, () -> model.invalidateDestination("CANCELLED"));
      try {
        if (detail >= 0 && detail < state.candidates().length()) {
          button(
              results,
              R.string.destination_details_hide,
              false,
              () -> {
                detail = -1;
                rendered = "";
                update();
              });
          JSONObject c = state.candidates().getJSONObject(detail);
          LinearLayout card = VehicleUi.card(activity);
          results.addView(card);
          text(card, c.getString("name"), 28);
          text(card, c.getString("address"), 22);
          text(card, c.getString("identitySourceUrl"), 20);
          JSONArray assessments = c.getJSONArray("assessments");
          for (int j = 0; j < assessments.length(); j++) {
            JSONObject a = assessments.getJSONObject(j);
            text(
                card, needLabel(a.getString("need")) + " · " + verdict(a.getString("verdict")), 24);
            evidence(card, a.getJSONArray("evidence"));
          }
          text(card, activity.getString(R.string.destination_reviews), 24);
          evidence(card, c.getJSONArray("reviews"));
        } else {
          LinearLayout cards = wide ? row() : column();
          results.addView(cards);
          for (int i = 0; i < state.candidates().length(); i++) {
            final int index = i;
            JSONObject c = state.candidates().getJSONObject(i);
            LinearLayout card = VehicleUi.card(activity);
            addCard(cards, card, wide);
            text(card, c.getString("name"), 28);
            text(card, c.getString("address"), 22);
            JSONArray assessments = c.getJSONArray("assessments");
            for (int j = 0; j < assessments.length(); j++) {
              JSONObject a = assessments.getJSONObject(j);
              text(
                  card,
                  needLabel(a.getString("need")) + " · " + verdict(a.getString("verdict")),
                  24);
            }
            JSONArray questions = c.getJSONArray("remainingQuestions");
            List<String> missing = new ArrayList<>();
            for (int q = 0; q < questions.length(); q++)
              missing.add(needLabel(questions.getString(q)));
            if (!missing.isEmpty())
              text(
                  card,
                  activity.getString(R.string.destination_missing, String.join(" · ", missing)),
                  22);
            if (BuildConfig.REAL_PLACE_DEMO && "RESTAURANT".equals(c.optString("venueCategory"))) {
              int ordinal = c.getInt("ordinal");
              Button navigate =
                  button(
                      card,
                      R.string.navigation_place_select,
                      true,
                      () ->
                          activity.selectNavigationRestaurant(
                              ordinal, c.optString("name"), c.optString("address")));
              navigate.setContentDescription(
                  activity.getString(R.string.navigation_place_candidate, ordinal));
              navigate.setEnabled(model.canWrite());
              text(card, activity.getString(R.string.navigation_place_next), 20);
            }
            if (model.phoneTask() != null) {
              int ordinal = c.getInt("ordinal");
              Button select =
                  button(
                      card,
                      R.string.phone_select,
                      true,
                      () ->
                          phonePanel.select(
                              ordinal,
                              c.optString("name"),
                              adultCount + childCount,
                              activity.getString(R.string.phone_base_conditions)
                                  + (highChair.isSelected()
                                      ? " · " + activity.getString(R.string.destination_high_chair)
                                      : "")
                                  + (stepFree.isSelected()
                                      ? " · " + activity.getString(R.string.destination_step_free)
                                      : "")));
              select.setContentDescription(activity.getString(R.string.phone_candidate, ordinal));
              select.setEnabled(
                  model.canWrite() && model.phone.canApprove(model.phoneTask(), ordinal));
            }
            button(
                card,
                R.string.destination_details_show,
                false,
                () -> {
                  detail = index;
                  rendered = "";
                  update();
                  view.smoothScrollTo(0, 0);
                });
          }
        }
      } catch (Exception invalid) {
        results.removeAllViews();
        text(results, activity.getString(R.string.destination_failed), 24);
      }
      view.post(() -> view.smoothScrollTo(0, 0));
      return;
    }
    int label =
        switch (state.state()) {
          case "LOADING", "QUEUED", "RUNNING" -> R.string.destination_loading;
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
    text(results, activity.getString(label), 24);
  }

  private String verdict(String value) {
    return activity.getString(
        switch (value) {
          case "YES" -> R.string.destination_yes;
          case "NO" -> R.string.destination_no;
          case "CONFLICT" -> R.string.destination_conflict;
          default -> R.string.destination_unknown;
        });
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

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(android.view.Gravity.CENTER_VERTICAL);
    return l;
  }

  private void weighted(LinearLayout parent, View child) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(dp(4), dp(4), dp(4), dp(4));
    parent.addView(child, p);
  }

  private void addCard(LinearLayout parent, View child, boolean horizontal) {
    if (horizontal) weighted(parent, child);
    else parent.addView(child);
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView t = new TextView(activity);
    t.setText(value);
    VehicleUi.text(t, size);
    parent.addView(t);
    return t;
  }

  private Button choice(LinearLayout parent, String value, Runnable action) {
    Button b = new Button(activity);
    b.setText(value);
    style(b, false);
    b.setOnClickListener(v -> action.run());
    weighted(parent, b);
    inputs.add(b);
    return b;
  }

  private Button button(LinearLayout parent, int label, boolean primary, Runnable action) {
    Button b = new Button(activity);
    b.setText(label);
    style(b, primary);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(
            parent.getOrientation() == LinearLayout.HORIZONTAL ? -2 : -1, -2);
    p.setMargins(dp(4), dp(4), dp(4), dp(4));
    parent.addView(b, p);
    return b;
  }

  private void style(Button button, boolean selected) {
    VehicleUi.button(button, selected);
    // Restyle the current input state explicitly when selection/restriction changes.
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(dp(16));
    shape.setColor(
        activity.getColor(
            !button.isEnabled()
                ? R.color.ui_disabled
                : selected ? R.color.ui_accent : R.color.ui_surface));
    if (!selected) shape.setStroke(dp(1), activity.getColor(R.color.ui_outline));
    button.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22000000), shape, null));
  }

  private int dp(int value) {
    return VehicleUi.dp(activity, value);
  }
}
