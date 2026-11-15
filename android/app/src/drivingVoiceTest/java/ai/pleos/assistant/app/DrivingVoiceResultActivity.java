package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.DrivingRestrictionController;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only receipt for the voice test, shown after the platform lifts driving restrictions. */
public final class DrivingVoiceResultActivity extends Activity {
  private DrivingRestrictionController driving;
  private SharedPreferences journal;
  private boolean parked;
  private final SharedPreferences.OnSharedPreferenceChangeListener journalChanged =
      (preferences, key) -> render();

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    journal = getSharedPreferences(DrivingVoiceReceiver.PREFERENCES, MODE_PRIVATE);
    driving =
        new DrivingRestrictionController(
            CarRestrictionSource.create(this),
            value -> {
              parked = value.restriction().allowsInput();
              render();
            });
  }

  @Override
  protected void onStart() {
    super.onStart();
    journal.registerOnSharedPreferenceChangeListener(journalChanged);
    render();
    driving.start();
  }

  @Override
  protected void onStop() {
    journal.unregisterOnSharedPreferenceChangeListener(journalChanged);
    parked = false;
    driving.stop();
    super.onStop();
  }

  private void render() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(getColor(R.color.ui_background));
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    int padding = VehicleUi.dp(this, 36);
    page.setPadding(padding, padding, padding, padding);
    scroll.addView(page);
    label(page, getString(R.string.driving_voice_results), 40, true);
    LinearLayout card = VehicleUi.card(this);
    page.addView(card);
    if (parked) showReceipt(card);
    else label(card, getString(R.string.driving_voice_parked_only), 28, false);
    setContentView(scroll);
  }

  private void showReceipt(LinearLayout card) {
    try {
      JSONObject saved = new JSONObject(journal.getString(DrivingVoiceReceiver.JOURNAL, "{}"));
      JSONObject receipt = saved.optJSONObject("receipt");
      if (receipt != null
          && "MOCK".equals(receipt.optString("mode"))
          && "CONFIRMED".equals(receipt.optString("status"))
          && receipt.optInt("externalCalls", -1) == 0) {
        label(card, getString(R.string.driving_voice_confirmed), 48, true);
        label(
            card,
            getString(
                R.string.driving_voice_restaurant,
                receipt.getInt("candidateOrdinal"),
                receipt.getString("restaurantName")),
            36,
            true);
        label(
            card,
            getString(
                R.string.driving_voice_booking_details,
                receipt.getString("date"),
                receipt.getString("confirmedTime"),
                receipt.getInt("partySize")),
            36,
            false);
        label(card, getString(R.string.driving_voice_no_calls, 0), 24, false);
      } else if (saved.has("taskId")) {
        label(card, getString(R.string.driving_voice_unconfirmed), 28, false);
      } else {
        label(card, getString(R.string.driving_voice_no_receipt), 28, false);
        label(card, getString(R.string.driving_voice_start_hint), 22, false);
      }
    } catch (JSONException invalid) {
      card.removeAllViews();
      label(card, getString(R.string.driving_voice_unconfirmed), 28, false);
    }
  }

  private void label(LinearLayout page, String value, int size, boolean emphasis) {
    TextView text = new TextView(this);
    VehicleUi.text(text, size);
    if (emphasis) text.setTypeface(text.getTypeface(), Typeface.BOLD);
    text.setText(value);
    page.addView(text);
  }
}
