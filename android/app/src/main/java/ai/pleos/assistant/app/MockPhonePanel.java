package ai.pleos.assistant.app;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONObject;

/** Button-only MOCK approval and durable GET recovery, separate from real booking authority. */
final class MockPhonePanel {
  final LinearLayout view;
  private final ReservationActivity activity;
  private final ReservationViewModel model;
  private AlertDialog dialog;
  private int scenario;
  private boolean conversationOpen;
  private boolean debugOpen;
  private String rendered = "";
  private static final String[] CASES = {"ACCEPT", "UNMET", "AMBIGUOUS", "DECLINE"};
  private static final int[] LABELS = {
    R.string.phone_accept, R.string.phone_unmet, R.string.phone_unknown, R.string.phone_decline
  };

  MockPhonePanel(ReservationActivity activity, ReservationViewModel model) {
    this.activity = activity;
    this.model = model;
    view = VehicleUi.card(activity);
  }

  void select(int ordinal, String name, int party, String conditions) {
    String task = model.phoneTask();
    if (task == null || !model.canWrite() || !model.phone.canApprove(task, ordinal)) return;
    try {
      JSONObject original = model.phone.approval();
      String at =
          original.optString(
              "reservationAt",
              ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                  .plusDays(1)
                  .withHour(19)
                  .withMinute(0)
                  .withSecond(0)
                  .withNano(0)
                  .toInstant()
                  .toString());
      JSONObject body =
          new JSONObject()
              .put("approved", true)
              .put("candidateOrdinal", ordinal)
              .put("reservationAt", at)
              .put("reservationName", "가상 예약자")
              .put("conditionScenario", scenario == 1 ? "UNMET" : "MET")
              .put("availabilityScenario", "AVAILABLE")
              .put("bookingScenario", scenario == 1 ? "ACCEPT" : CASES[scenario]);
      String time =
          java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")
              .withZone(ZoneId.of("Asia/Seoul"))
              .format(java.time.Instant.parse(at));
      LinearLayout content = VehicleUi.card(activity);
      text(content, activity.getString(R.string.final_approval_step), 20);
      text(content, activity.getString(R.string.phone_approve_title), 34);
      text(content, name, 30);
      text(content, activity.getString(R.string.final_approval_summary, time, party), 28);
      text(content, activity.getString(R.string.final_approval_conditions, conditions), 24);
      text(content, activity.getString(R.string.final_approval_notice), 20);
      android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
      scroll.addView(content);
      dialog =
          new AlertDialog.Builder(activity)
              .setView(scroll)
              .setNegativeButton(R.string.phone_cancel, null)
              .setPositiveButton(
                  R.string.phone_approve, (d, w) -> model.approvePhone(task, body, party))
              .create();
      dialog.show();
      VehicleUi.button(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
      VehicleUi.button(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
      if (dialog.getWindow() != null) {
        int width = activity.getWindow().getDecorView().getWidth();
        dialog
            .getWindow()
            .setLayout(
                Math.min(width - VehicleUi.dp(activity, 48), VehicleUi.dp(activity, 1100)), -2);
      }
    } catch (Exception invalid) {
      rendered = "";
    }
  }

  void update() {
    if (model.phone == null || !BuildConfig.MOCK_PHONE_DEMO) {
      view.setVisibility(View.GONE);
      return;
    }
    view.setVisibility(
        model.phone.hasRequest() || model.phoneTask() != null ? View.VISIBLE : View.GONE);
    model.phone.allow(model.canWrite());
    if (dialog != null && (!model.canWrite() || model.phoneTask() == null)) dialog.dismiss();
    String signature =
        model.phone.state()
            + model.phone.busy()
            + model.phone.receipt()
            + model.canWrite()
            + scenario
            + debugOpen
            + conversationOpen
            + model.phoneTask();
    if (signature.equals(rendered)) return;
    rendered = signature;
    view.removeAllViews();
    if (!model.phone.hasRequest()) {
      button(
          activity.getString(R.string.final_debug),
          model.canWrite(),
          () -> {
            debugOpen = !debugOpen;
            rendered = "";
            update();
          });
      if (!debugOpen) return;
    }
    if (conversationOpen && !model.canWrite()) conversationOpen = false;
    if (conversationOpen) {
      renderConversation(model.phone.receipt());
      return;
    }
    if (!model.phone.hasRequest() || model.phone.state().equals("DECLINED"))
      button(
          activity.getString(R.string.phone_scenario, activity.getString(LABELS[scenario])),
          model.canWrite() && !model.phone.busy(),
          () -> {
            scenario = (scenario + 1) % CASES.length;
            rendered = "";
            update();
          });
    String state = model.phone.state();
    label(
        activity.getString(
            switch (state) {
              case "SENDING" -> R.string.phone_sending;
              case "CONFIRMED" -> R.string.phone_confirmed;
              case "DECLINED" -> R.string.phone_declined;
              case "UNKNOWN" -> R.string.phone_uncertain;
              case "FAILED" -> R.string.phone_failed;
              case "STORAGE_ERROR" -> R.string.phone_storage;
              default -> R.string.phone_choose;
            }),
        34);
    JSONObject receipt = model.phone.receipt();
    if (receipt.has("attemptId")) {
      label(receipt.optString("restaurantName"), 30);
      label(
          activity.getString(
              R.string.final_result_summary,
              receipt.optString("date"),
              "CONFIRMED".equals(state)
                  ? receipt.optString("confirmedTime", activity.getString(R.string.phone_unknown))
                  : receipt.optString("requestedTime"),
              receipt.optInt("partySize")),
          36);
      label(
          activity.getString(
              R.string.phone_stages,
              statusLabel(receipt.optString("conditionStatus")),
              statusLabel(receipt.optString("availabilityStatus")),
              receipt.optBoolean("bookingRequested")
                  ? activity.getString(R.string.phone_requested)
                  : activity.getString(R.string.phone_not_requested)),
          20);
      button(
          activity.getString(R.string.phone_conversation_show),
          model.canWrite(),
          () -> {
            conversationOpen = true;
            rendered = "";
            update();
          });
      JSONObject next = receipt.optJSONObject("nextCandidate");
      if ("DECLINED".equals(state) && next != null)
        label(activity.getString(R.string.phone_next, next.optInt("ordinal")), 22);
    }
    if (model.phone.hasRequest())
      button(activity.getString(R.string.phone_refresh), !model.phone.busy(), model.phone::refresh);
  }

  private void renderConversation(JSONObject receipt) {
    label(activity.getString(R.string.final_conversation_title), 34);
    label(activity.getString(R.string.phone_conversation_notice), 20);
    var conversation = receipt.optJSONArray("conversation");
    if (conversation == null || conversation.length() == 0)
      label(activity.getString(R.string.phone_conversation_empty), 24);
    else
      for (int i = 0; i < conversation.length(); i++) {
        JSONObject turn = conversation.optJSONObject(i);
        if (turn == null || turn.optString("message").isBlank()) continue;
        int speaker =
            switch (turn.optString("speaker")) {
              case "agent" -> R.string.phone_speaker_agent;
              case "restaurant" -> R.string.phone_speaker_restaurant;
              default -> R.string.phone_speaker_other;
            };
        LinearLayout card = VehicleUi.card(activity);
        text(card, activity.getString(speaker), 20);
        text(card, turn.optString("message"), 24);
        view.addView(card);
      }
    button(
        activity.getString(R.string.phone_conversation_hide),
        true,
        () -> {
          conversationOpen = false;
          rendered = "";
          update();
        });
  }

  private void text(LinearLayout parent, String value, int size) {
    TextView t = new TextView(activity);
    t.setText(value);
    VehicleUi.text(t, size);
    parent.addView(t);
  }

  private String statusLabel(String state) {
    return activity.getString(
        switch (state) {
          case "MET" -> R.string.phone_met;
          case "UNMET" -> R.string.phone_unmet;
          case "AVAILABLE" -> R.string.phone_available;
          case "UNAVAILABLE" -> R.string.phone_unavailable;
          default -> R.string.phone_unknown;
        });
  }

  private void label(String text, int size) {
    TextView t = new TextView(activity);
    t.setText(text);
    VehicleUi.text(t, size);
    view.addView(t);
  }

  private void button(String text, boolean enabled, Runnable action) {
    Button b = new Button(activity);
    b.setText(text);
    VehicleUi.button(b, text.equals(activity.getString(R.string.phone_refresh)));
    b.setMinHeight((int) (72 * activity.getResources().getDisplayMetrics().density));
    b.setEnabled(enabled);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = VehicleUi.dp(activity, 12);
    view.addView(b, params);
  }
}
