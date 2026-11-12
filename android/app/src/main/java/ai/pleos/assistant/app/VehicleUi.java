package ai.pleos.assistant.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Semantic day/night colors, wrapping text and generous targets shared by both MOCK flows. */
final class VehicleUi {
  private VehicleUi() {}

  static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static ColorStateList states(Context c, int enabled, int disabled) {
    return new ColorStateList(
        new int[][] {{-android.R.attr.state_enabled}, {}},
        new int[] {c.getColor(disabled), c.getColor(enabled)});
  }

  static void text(TextView view, int size) {
    Context c = view.getContext();
    view.setTextSize(size);
    view.setTextColor(states(c, R.color.ui_text, R.color.ui_on_disabled));
    view.setPadding(0, dp(c, 6), 0, dp(c, 6));
  }

  static void input(android.widget.EditText field) {
    text(field, 24);
    field.setSingleLine(true);
    field.setMinHeight(dp(field.getContext(), 72));
    field.setMinimumHeight(dp(field.getContext(), 72));
    field.setImeOptions(
        android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    field.setOnEditorActionListener(
        (view, action, event) -> {
          if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
          ((android.view.inputmethod.InputMethodManager)
                  view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
              .hideSoftInputFromWindow(view.getWindowToken(), 0);
          view.clearFocus();
          return true;
        });
  }

  static void button(Button b, boolean primary) {
    Context c = b.getContext();
    b.setAllCaps(false);
    b.setTextSize(22);
    b.setMinHeight(dp(c, 72));
    b.setMaxLines(5);
    b.setTextColor(
        states(c, primary ? R.color.ui_on_accent : R.color.ui_text, R.color.ui_on_disabled));
    android.graphics.drawable.StateListDrawable background =
        new android.graphics.drawable.StateListDrawable();
    background.addState(
        new int[] {-android.R.attr.state_enabled}, buttonShape(c, R.color.ui_disabled));
    background.addState(
        new int[] {}, buttonShape(c, primary ? R.color.ui_accent : R.color.ui_surface));
    b.setBackgroundTintList(null);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf((c.getColor(R.color.ui_outline) & 0x00ffffff) | 0x22000000),
            background,
            null));
    b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
  }

  private static GradientDrawable buttonShape(Context context, int color) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(context.getColor(color));
    shape.setCornerRadius(dp(context, 16));
    if (color == R.color.ui_surface)
      shape.setStroke(dp(context, 1), context.getColor(R.color.ui_outline));
    return shape;
  }

  static LinearLayout card(Context c) {
    LinearLayout card = new LinearLayout(c);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
    GradientDrawable background = new GradientDrawable();
    background.setColor(c.getColor(R.color.ui_surface));
    background.setCornerRadius(dp(c, 16));
    background.setStroke(dp(c, 1), c.getColor(R.color.ui_outline));
    card.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(c, 8), 0, dp(c, 8));
    card.setLayoutParams(params);
    return card;
  }

  static void disclosure(Button button, View details, int open, int close) {
    details.setVisibility(View.GONE);
    button.setText(open);
    button.setOnClickListener(
        v -> {
          boolean show = details.getVisibility() != View.VISIBLE;
          details.setVisibility(show ? View.VISIBLE : View.GONE);
          button.setText(show ? close : open);
        });
  }
}
