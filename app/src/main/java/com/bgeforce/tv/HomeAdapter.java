package com.bgeforce.tv;

import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;

final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
  interface Actions {
    void game(Game game);

    void all();

    void genre(String label);
  }

  private final MainActivity activity;
  private final Images images;
  private final L l;
  private final Actions actions;
  private final List<Catalog.Shelf> shelves;
  private final List<Game> banners;
  private final List<String> genres;
  private final Map<String, android.os.Parcelable> scrollStates;

  HomeAdapter(
      MainActivity a,
      Images images,
      L l,
      List<Catalog.Shelf> shelves,
      List<Game> banners,
      List<String> genres,
      Map<String, android.os.Parcelable> scrollStates,
      Actions actions) {
    activity = a;
    this.images = images;
    this.l = l;
    this.actions = actions;
    this.shelves = shelves;
    this.banners = banners;
    this.genres = genres;
    this.scrollStates = scrollStates;
    setHasStableIds(true);
  }

  @Override
  public long getItemId(int pos) {
    return pos;
  }

  @Override
  public int getItemCount() {
    return shelves.size() + 3;
  }

  @Override
  public int getItemViewType(int pos) {
    return pos == 0 ? 0 : pos <= shelves.size() ? 1 : pos == shelves.size() + 1 ? 2 : 3;
  }

  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
    if (type == 0) return new Hero();
    if (type == 1) return new ShelfHolder();
    LinearLayout col = Ui.column(activity);
    col.setPadding(0, Ui.dp(activity, 10), Ui.dp(activity, 20), Ui.dp(activity, 20));
    col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
    return new RecyclerView.ViewHolder(col) {};
  }

  @Override
  public void onBindViewHolder(RecyclerView.ViewHolder holder, int pos) {
    int type = getItemViewType(pos);
    if (type == 0) {
      ((Hero) holder).set();
      return;
    }
    if (type == 1) {
      ((ShelfHolder) holder).bind(shelves.get(pos - 1));
      return;
    }
    LinearLayout box = (LinearLayout) holder.itemView;
    box.removeAllViews();
    if (type == 2) {
      box.addView(Ui.title(activity, l.t("genres"), 20));
      Ui.gap(box, 12);
      for (int start = 0; start < genres.size(); start += 4) {
        LinearLayout row = Ui.row(activity);
        for (int i = start; i < Math.min(start + 4, genres.size()); i++) {
          String genre = genres.get(i);
          TextView b = Ui.button(activity, genre, () -> actions.genre(genre));
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 64), 1);
          lp.setMargins(0, 0, Ui.dp(activity, 10), Ui.dp(activity, 10));
          row.addView(b, lp);
        }
        box.addView(row);
      }
    } else {
      TextView all = Ui.button(activity, l.t("all") + "    →", actions::all);
      all.setTextColor(Ui.ACCENT);
      box.addView(all, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 64)));
      Ui.gap(box, 12);
      box.addView(
          Ui.text(activity, "BGeforce Now TV  •  " + BuildConfig.VERSION_NAME, 11, Ui.MUTED));
    }
  }

  @Override
  public void onViewAttachedToWindow(RecyclerView.ViewHolder h) {
    if (h instanceof Hero) ((Hero) h).start();
  }

  @Override
  public void onViewDetachedFromWindow(RecyclerView.ViewHolder h) {
    if (h instanceof Hero) ((Hero) h).stop();
    if (h instanceof ShelfHolder) ((ShelfHolder) h).save();
  }

  final class ShelfHolder extends RecyclerView.ViewHolder {
    final TextView title;
    final RecyclerView list;
    String shelfId = "";

    ShelfHolder() {
      super(Ui.column(activity));
      LinearLayout col = (LinearLayout) itemView;
      col.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 10));
      col.setLayoutParams(
          new RecyclerView.LayoutParams(-1, Ui.dp(activity, Ui.cardWidth(activity) + 100)));
      title = Ui.title(activity, "", 19);
      col.addView(title);
      Ui.gap(col, 10);
      list = new RecyclerView(activity);
      LinearLayoutManager manager =
          new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false);
      manager.setInitialPrefetchItemCount(2);
      list.setLayoutManager(manager);
      list.setItemAnimator(null);
      list.setHasFixedSize(true);
      list.setItemViewCacheSize(1);
      list.setClipToPadding(false);
      list.setPadding(
          Ui.dp(activity, 2), Ui.dp(activity, 2), Ui.dp(activity, 22), Ui.dp(activity, 4));
      col.addView(list, new LinearLayout.LayoutParams(-1, -1));
    }

    void save() {
      if (!shelfId.isEmpty())
        scrollStates.put(shelfId, list.getLayoutManager().onSaveInstanceState());
    }

    void bind(Catalog.Shelf shelf) {
      save();
      shelfId = shelf.id;
      title.setText(shelf.title);
      list.setAdapter(new Cards(shelf.games));
      android.os.Parcelable state = scrollStates.get(shelfId);
      if (state != null) list.getLayoutManager().onRestoreInstanceState(state);
    }
  }

  final class Cards extends RecyclerView.Adapter<Cards.Holder> {
    final List<Game> games;

    Cards(List<Game> games) {
      this.games = games;
    }

    final class Holder extends RecyclerView.ViewHolder {
      Holder(GameCard v) {
        super(v);
      }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup p, int type) {
      GameCard card = new GameCard(activity);
      RecyclerView.LayoutParams lp =
          new RecyclerView.LayoutParams(
              Ui.dp(activity, Ui.cardWidth(activity)),
              Ui.dp(activity, Ui.cardWidth(activity) + 48));
      lp.setMargins(0, 0, Ui.dp(activity, 10), 0);
      card.setLayoutParams(lp);
      return new Holder(card);
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
      Game game = games.get(position);
      ((GameCard) h.itemView).bind(game, images, () -> actions.game(game));
    }

    @Override
    public int getItemCount() {
      return games.size();
    }
  }

  final class Hero extends RecyclerView.ViewHolder {
    final FrameLayout frame;
    final ImageView art;
    final TextView title, description, indicator;
    final TextView play;
    final Handler handler = new Handler(Looper.getMainLooper());
    int index;
    final Runnable tick =
        new Runnable() {
          public void run() {
            if (activity.hasWindowFocus()
                && frame.isShown()
                && !frame.hasFocus()
                && banners.size() > 1) {
              index = (index + 1) % banners.size();
              set();
            }
            handler.postDelayed(this, 8000);
          }
        };

    Hero() {
      super(
          new FrameLayout(activity) {
            @Override
            public void requestChildFocus(View child, View focused) {
              super.requestChildFocus(child, focused);
              if (getParent() instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) getParent();
                if (rv.getLayoutManager() instanceof LinearLayoutManager)
                  ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(0, 0);
              }
            }
          });
      frame = (FrameLayout) itemView;
      frame.setLayoutParams(
          new RecyclerView.LayoutParams(-1, Ui.dp(activity, Ui.heroHeight(activity))));
      frame.setBackground(Ui.bg(Ui.PANEL, Ui.dp(activity, 18)));
      frame.setClipToOutline(true);
      art = new ImageView(activity);
      art.setScaleType(ImageView.ScaleType.CENTER_CROP);
      frame.addView(art, new FrameLayout.LayoutParams(-1, -1));
      View fade = new View(activity);
      fade.setBackground(
          new GradientDrawable(
              GradientDrawable.Orientation.LEFT_RIGHT,
              new int[] {0xff070a0e, 0xe6070a0e, 0x00070a0e}));
      frame.addView(fade, new FrameLayout.LayoutParams(-1, -1));
      View bottom = new View(activity);
      bottom.setBackground(
          new GradientDrawable(
              GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00050b10, Ui.BG}));
      FrameLayout.LayoutParams bf =
          new FrameLayout.LayoutParams(-1, Ui.dp(activity, 80), Gravity.BOTTOM);
      frame.addView(bottom, bf);
      LinearLayout text = Ui.column(activity);
      text.setPadding(Ui.dp(activity, 30), Ui.dp(activity, 20), Ui.dp(activity, 16), 0);
      FrameLayout.LayoutParams tp =
          new FrameLayout.LayoutParams(
              Ui.dp(
                  activity,
                  Math.min(
                      380,
                      Math.max(280, activity.getResources().getConfiguration().screenWidthDp / 2))),
              -1);
      frame.addView(text, tp);
      TextView badge =
          Ui.text(
              activity,
              "GEFORCE NOW  /  " + l.t("featured").toUpperCase(Locale.ROOT),
              11,
              Ui.ACCENT);
      text.addView(badge);
      Ui.gap(text, 12);
      title =
          Ui.title(
              activity,
              "",
              activity.getResources().getConfiguration().screenWidthDp < 800 ? 25 : 29);
      title.setMaxLines(activity.getResources().getConfiguration().screenHeightDp < 480 ? 1 : 2);
      text.addView(title);
      Ui.gap(text, 8);
      description = Ui.text(activity, "", 13, Ui.MUTED);
      description.setMaxLines(
          activity.getResources().getConfiguration().screenHeightDp < 480 ? 1 : 2);
      text.addView(description);
      Ui.gap(text, 16);
      play =
          Ui.button(
              activity,
              "▶  " + l.t("play"),
              () -> {
                if (!banners.isEmpty()) actions.game(banners.get(index));
              });
      play.setId(R.id.home_primary);
      play.setNextFocusUpId(R.id.nav_home);
      Ui.primary(play);
      text.addView(play, Ui.lp(activity, 190, 44));
      LinearLayout arrows = Ui.row(activity);
      arrows.addView(Ui.button(activity, "‹", () -> change(-1)), Ui.lp(activity, 50, 40));
      arrows.addView(Ui.button(activity, "›", () -> change(1)), Ui.lp(activity, 50, 40));
      FrameLayout.LayoutParams ap =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
      ap.setMargins(0, 0, Ui.dp(activity, 20), Ui.dp(activity, 40));
      frame.addView(arrows, ap);
      play.setOnKeyListener(
          (v, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_LEFT)) {
              change(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
              return true;
            }
            return false;
          });
      play.setOnFocusChangeListener(
          (v, focused) -> {
            Ui.focusChanged(v, focused);
            if (focused && frame.getParent() instanceof RecyclerView)
              ((RecyclerView) frame.getParent()).smoothScrollToPosition(0);
          });
      indicator = Ui.text(activity, "", 13, Ui.TEXT);
      indicator.setPadding(0, 0, Ui.dp(activity, 24), Ui.dp(activity, 20));
      indicator.setGravity(Gravity.CENTER);
      frame.addView(indicator, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    void change(int delta) {
      if (banners.isEmpty()) return;
      index = (index + delta + banners.size()) % banners.size();
      set();
      Ui.enter(title);
      handler.removeCallbacks(tick);
      handler.postDelayed(tick, 8000);
    }

    void set() {
      if (banners.isEmpty()) {
        title.setText("BGeforce Now TV");
        description.setText(l.t("loading"));
        play.setVisibility(View.GONE);
        return;
      }
      if (index >= banners.size()) index = 0;
      Game game = banners.get(index);
      title.setText(game.title);
      description.setText(
          game.description.isEmpty() ? Json.join(" · ", game.genres) : game.description);
      images.load(art, game.hero, 1100, 620);
      StringBuilder dots = new StringBuilder();
      for (int i = 0; i < Math.min(8, banners.size()); i++)
        dots.append(i == index % 8 ? "●  " : "·  ");
      indicator.setText(dots.toString());
      play.setVisibility(View.VISIBLE);
    }

    void start() {
      handler.removeCallbacks(tick);
      handler.postDelayed(tick, 8000);
    }

    void stop() {
      Ui.cancelAnimations(frame);
      handler.removeCallbacks(tick);
      art.setImageDrawable(null);
    }
  }
}
