package com.bgeforce.tv;

import android.content.Context;
import android.view.*;
import android.widget.*;

final class GameCard extends LinearLayout {
  final ImageView artwork;
  final TextView title, store;

  GameCard(Context c) {
    super(c);
    setOrientation(VERTICAL);
    setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 4));
    setFocusable(true);
    setClickable(true);
    Ui.focus(this);
    artwork = new ImageView(c);
    artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    artwork.setBackground(Ui.bg(Ui.PANEL, Ui.dp(c, 8)));
    artwork.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(c, 9));
          }
        });
    artwork.setClipToOutline(true);
    addView(artwork, new LayoutParams(-1, Ui.dp(c, Ui.cardWidth(c) - 6)));
    title = Ui.title(c, "", 12);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), 0);
    addView(title, new LayoutParams(-1, -2));
    store = Ui.text(c, "", 10, Ui.MUTED);
    store.setSingleLine(true);
    store.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), 0);
    addView(store, new LayoutParams(-1, -2));
  }

  void bind(Game game, Images images, Runnable click) {
    setTag(game.id);
    title.setText(game.title);
    Game.Variant v = game.selected();
    store.setText(v == null ? game.publisher : v.store + (game.owned() ? "  •  ✓" : ""));
    setContentDescription(game.title);
    images.load(artwork, game.cover, 320, 320);
    setOnClickListener(view -> click.run());
  }
}
