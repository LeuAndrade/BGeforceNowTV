package com.bgeforce.tv;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

public final class Ui {
  public static final int BG = Color.rgb(7, 10, 14),
      PANEL = Color.rgb(20, 25, 33),
      TEXT = Color.rgb(241, 245, 248),
      MUTED = Color.rgb(166, 177, 194),
      ACCENT = Color.rgb(111, 221, 151),
      BLUE = Color.rgb(174, 205, 255);

  private Ui() {}

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable bg(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radius);
    return g;
  }

  public static TextView text(Context c, String s, int size, int color) {
    TextView v = new TextView(c);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setFontFeatureSettings("kern");
    return v;
  }

  public static TextView title(Context c, String s, int size) {
    TextView v = text(c, s, size, TEXT);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  public static TextView button(Context c, String s, Runnable click) {
    TextView b = title(c, s, 14);
    b.setGravity(Gravity.CENTER_VERTICAL);
    b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
    b.setMinHeight(dp(c, 44));
    b.setFocusable(true);
    b.setClickable(true);
    focus(b);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  public static void focus(View v) {
    Context c = v.getContext();
    GradientDrawable normal = bg(PANEL, dp(c, 12));
    normal.setStroke(dp(c, 1), 0xff262e3a);
    GradientDrawable focused =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff28364a, 0xff17202d});
    focused.setCornerRadius(dp(c, 12));
    focused.setStroke(dp(c, 2), BLUE);
    GradientDrawable selected = bg(0xff1b2634, dp(c, 12));
    selected.setStroke(dp(c, 1), 0xff55749a);
    StateListDrawable state = new StateListDrawable();
    state.addState(new int[] {android.R.attr.state_focused}, focused);
    state.addState(new int[] {android.R.attr.state_pressed}, focused);
    state.addState(new int[] {android.R.attr.state_selected}, selected);
    state.addState(new int[] {}, normal);
    v.setBackground(state);
    v.setOnFocusChangeListener(Ui::focusChanged);
  }

  public static void focusChanged(View view, boolean has) {
    if (view.getContext() instanceof StreamActivity) return;
    view.animate().cancel();
    if (!motion()) {
      view.setScaleX(1);
      view.setScaleY(1);
      return;
    }
    view.animate()
        .scaleX(has ? 1.018f : 1)
        .scaleY(has ? 1.018f : 1)
        .translationY(has ? -dp(view.getContext(), 1) : 0)
        .setDuration(has ? 130 : 100)
        .setInterpolator(new android.view.animation.DecelerateInterpolator())
        .start();
  }

  public static void primary(TextView v) {
    Context c = v.getContext();
    GradientDrawable normal =
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xfff5fff9, 0xffc5d8fa});
    normal.setCornerRadius(dp(c, 28));
    GradientDrawable focused =
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xffffffff, 0xffdce7ff});
    focused.setCornerRadius(dp(c, 28));
    focused.setStroke(dp(c, 2), BLUE);
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {android.R.attr.state_focused}, focused);
    states.addState(new int[] {android.R.attr.state_pressed}, focused);
    states.addState(new int[] {}, normal);
    v.setTextColor(BG);
    v.setBackground(states);
    v.setGravity(Gravity.CENTER);
  }

  private static boolean motion() {
    return android.os.Build.VERSION.SDK_INT < 26
        || android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  public static void enter(View v) {
    if (v.getContext() instanceof StreamActivity || !motion()) return;
    v.setAlpha(.65f);
    v.setTranslationY(dp(v.getContext(), 5));
    v.animate()
        .alpha(1)
        .translationY(0)
        .setDuration(170)
        .setInterpolator(new android.view.animation.DecelerateInterpolator())
        .start();
  }

  public static void cancelAnimations(View v) {
    if (v == null) return;
    v.animate().cancel();
    v.setAlpha(1);
    v.setScaleX(1);
    v.setScaleY(1);
    v.setTranslationY(0);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) cancelAnimations(g.getChildAt(i));
    }
  }

  public static int cardWidth(Context c) {
    return Math.max(
        112, Math.min(156, (c.getResources().getConfiguration().screenWidthDp - 56) / 8));
  }

  public static int heroHeight(Context c) {
    return Math.max(
        216, Math.min(310, Math.round(c.getResources().getConfiguration().screenHeightDp * .44f)));
  }

  public static int columns(Context c) {
    return Math.max(4, Math.min(7, (c.getResources().getConfiguration().screenWidthDp - 64) / 155));
  }

  public static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static LinearLayout.LayoutParams lp(Context c, int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(c, w), h < 0 ? h : dp(c, h));
  }

  public static void gap(LinearLayout c, int height) {
    View v = new View(c.getContext());
    c.addView(v, lp(c.getContext(), 1, height));
  }

  public static void fullscreen(android.app.Activity a) {
    a.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
  }
}
