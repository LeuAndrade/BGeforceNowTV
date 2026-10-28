package com.bgeforce.tv;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor jobs =
      new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32));

  {
    // Keep the catalog resumable without retaining two idle worker stacks during a game.
    jobs.allowCoreThreadTimeOut(true);
  }

  private Api api;
  private Catalog catalog;
  private Settings settings;
  private L l;
  private Images images;
  private FrameLayout root, content;
  private LinearLayout side;
  private TextView status, searchButton, sectionTitle;
  private final Map<String, TextView> navButtons = new HashMap<>();
  private boolean webReturning;
  private String homeError = "";
  private android.os.Parcelable homeScroll;
  private final Map<String, android.os.Parcelable> shelfScroll = new HashMap<>();
  private RecyclerView homeList;
  private Catalog.Home home;
  private List<Game> library = new ArrayList<>();
  private JSONObject definitions = new JSONObject();
  private final Map<String, JSONObject> genreFilters = new LinkedHashMap<>();
  private String screen = "home";
  private volatile boolean destroyed, streaming;
  private volatile boolean visible;
  private boolean refreshing, activeBusy;
  private int activeGeneration;
  private List<SessionClient.Active> activeSessions = new ArrayList<>();
  private TextView resumeDetails, closeDetails, detailPlay;
  private boolean closingSession;
  private Game detailGame;
  private final Runnable sessionRefresh =
      new Runnable() {
        public void run() {
          if (!visible || streaming || destroyed) return;
          refreshActiveSessions();
          ui.postDelayed(this, 30000);
        }
      };
  private int epoch, gridEpoch;
  private volatile int authEpoch;
  private JSONObject challenge;
  private long challengeExpires;
  private int authInterval = 5;
  private TextView loginState;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          pollLogin();
        }
      };
  private final Runnable refreshTimer =
      new Runnable() {
        public void run() {
          if (visible && !streaming && api.signedIn()) refresh(false);
          ui.postDelayed(this, 2 * 60 * 1000);
        }
      };

  @FunctionalInterface
  private interface Work<T> {
    T run() throws Exception;
  }

  @FunctionalInterface
  private interface Done<T> {
    void accept(T value);
  }

  private <T> void async(Work<T> work, Done<T> done) {
    if (destroyed) return;
    try {
      jobs.execute(
          () -> {
            try {
              T value = work.run();
              ui.post(
                  () -> {
                    if (!destroyed && !streaming) done.accept(value);
                  });
            } catch (Exception e) {
              ui.post(
                  () -> {
                    if (!destroyed && !streaming) error(e);
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      toast(l.t("loading"));
    }
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    Ui.fullscreen(this);
    api = new Api(this);
    settings = api.settings;
    l = new L(settings.locale());
    images = new Images(this);
    catalog = new Catalog(this, api);
    try {
      api.restore();
    } catch (Exception e) {
      api.logout();
      android.webkit.CookieManager.getInstance().removeAllCookies(null);
      android.webkit.WebStorage.getInstance().deleteAllData();
      homeScroll = null;
      shelfScroll.clear();
      homeError = "";
    }
    if (api.signedIn()) {
      shell();
      home = catalog.cachedHome();
      showHome();
      refresh(false);
    } else login();
  }

  @Override
  protected void onResume() {
    super.onResume();
    visible = true;
    Ui.fullscreen(this);
    ui.removeCallbacks(refreshTimer);
    ui.postDelayed(refreshTimer, 2 * 60 * 1000);
    if (streaming) {
      closingSession = false;
      streaming = false;
      images = new Images(this);
      try {
        api.restore();
      } catch (Exception error) {
        error(error);
      }
      shell();
      home = catalog.cachedHome();
      showHome();
      refresh(false);
    }
    if (webReturning && api.signedIn()) {
      webReturning = false;
      connections();
      refresh(true);
    }
    if (!api.signedIn() && challenge != null) {
      ui.removeCallbacks(poll);
      ui.post(poll);
    }
    ui.removeCallbacks(sessionRefresh);
    ui.post(sessionRefresh);
  }

  @Override
  protected void onStop() {
    super.onStop();
    visible = false;
    activeGeneration++;
    activeBusy = false;
    ui.removeCallbacks(refreshTimer);
    ui.removeCallbacks(sessionRefresh);
    ui.removeCallbacks(poll);
    Ui.cancelAnimations(root);
    if (streaming) {
      epoch++;
      gridEpoch++;
      jobs.getQueue().clear();
      NetworkCleanup.release(api.http, null);
      images.close();
      if (homeList != null) {
        homeList.setAdapter(null);
        homeList = null;
      }
      home = null;
      resumeDetails = null;
      closeDetails = null;
      detailPlay = null;
      detailGame = null;
      activeSessions.clear();
      library.clear();
      if (content != null) content.removeAllViews();
    }
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    authEpoch++;
    ui.removeCallbacksAndMessages(null);
    jobs.getQueue().clear();
    NetworkCleanup.release(api.http, jobs);
    images.close();
    super.onDestroy();
  }

  private void shell() {
    epoch++;
    l = new L(settings.locale());
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    root.setLayoutDirection(
        settings.locale().getLanguage().equals("ar")
            ? View.LAYOUT_DIRECTION_RTL
            : View.LAYOUT_DIRECTION_LTR);
    setContentView(root);
    navButtons.clear();
    int width = getResources().getConfiguration().screenWidthDp;
    LinearLayout brand = Ui.row(this);
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_logo);
    brand.addView(logo, Ui.lp(this, 34, 34));
    LinearLayout words = Ui.column(this);
    words.setPadding(Ui.dp(this, 8), 0, 0, 0);
    words.addView(Ui.title(this, "BGeforce", 15));
    TextView sub = Ui.text(this, "NOW TV", 9, Ui.ACCENT);
    sub.setLetterSpacing(.2f);
    words.addView(sub);
    if (width >= 800) brand.addView(words);
    FrameLayout.LayoutParams bp =
        new FrameLayout.LayoutParams(-2, Ui.dp(this, 60), Gravity.TOP | Gravity.START);
    bp.setMarginStart(Ui.dp(this, 24));
    root.addView(brand, bp);
    side = Ui.row(this);
    side.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
    side.setBackground(Ui.bg(0xff11151c, Ui.dp(this, 32)));
    FrameLayout.LayoutParams np =
        new FrameLayout.LayoutParams(
            Ui.dp(this, Math.min(432, Math.max(344, width / 2))),
            Ui.dp(this, 48),
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    np.topMargin = Ui.dp(this, 7);
    root.addView(side, np);
    nav(
        "home",
        () -> {
          showHome();
          refresh(true);
        });
    nav("library", () -> showGrid(l.t("library"), true, "", null));
    nav("catalog", () -> showGrid(l.t("all"), false, "", null));
    nav("settings", this::showSettings);
    searchButton = Ui.button(this, "", this::searchDialog);
    searchButton.setContentDescription(l.t("search"));
    TvGlyph magnifier = new TvGlyph("search");
    magnifier.setBounds(0, 0, Ui.dp(this, 23), Ui.dp(this, 23));
    searchButton.setCompoundDrawables(magnifier, null, null, null);
    searchButton.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 11), 0);
    FrameLayout.LayoutParams sp =
        new FrameLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 44), Gravity.TOP | Gravity.END);
    sp.topMargin = Ui.dp(this, 9);
    sp.setMarginEnd(Ui.dp(this, 26));
    root.addView(searchButton, sp);
    status = Ui.text(this, api.userName(), 10, Ui.MUTED);
    status.setVisibility(View.GONE);
    sectionTitle = Ui.text(this, "", 12, Ui.MUTED);
    sectionTitle.setVisibility(View.GONE);
    content = new FrameLayout(this);
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
    cp.topMargin = Ui.dp(this, 66);
    cp.setMarginStart(Ui.dp(this, 24));
    cp.setMarginEnd(Ui.dp(this, 24));
    root.addView(content, cp);
  }

  private void nav(String key, Runnable click) {
    TextView v = Ui.button(this, l.nav(key), click);
    v.setId(
        key.equals("home")
            ? R.id.nav_home
            : key.equals("library")
                ? R.id.nav_library
                : key.equals("catalog") ? R.id.nav_catalog : R.id.nav_settings);
    v.setContentDescription(l.t(key));
    v.setSingleLine(true);
    v.setTextSize(12);
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
    TvGlyph icon = new TvGlyph(key);
    icon.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
    v.setCompoundDrawables(icon, null, null, null);
    v.setCompoundDrawablePadding(Ui.dp(this, 6));
    side.addView(v, new LinearLayout.LayoutParams(0, -1, 1));
    navButtons.put(key, v);
  }

  private void clear(String name) {
    if (homeList != null && homeList.getLayoutManager() != null)
      homeScroll = homeList.getLayoutManager().onSaveInstanceState();
    screen = name;
    if (searchButton != null)
      searchButton.setVisibility(Navigation.searchVisible(name) ? View.VISIBLE : View.GONE);
    if (sectionTitle != null) sectionTitle.setText(l.t(name));
    for (Map.Entry<String, TextView> entry : navButtons.entrySet()) {
      entry.getValue().setSelected(entry.getKey().equals(Navigation.active(name)));
      entry
          .getValue()
          .setNextFocusDownId(
              name.equals("home")
                  ? R.id.home_primary
                  : name.equals("library") || name.equals("catalog")
                      ? R.id.grid_first
                      : R.id.settings_first);
    }
    gridEpoch++;
    if (homeList != null) {
      homeList.setAdapter(null);
      homeList = null;
    }
    Ui.cancelAnimations(content);
    content.removeAllViews();
    resumeDetails = null;
    closeDetails = null;
    detailPlay = null;
    detailGame = null;
  }

  private void refresh(boolean force) {
    if (refreshing || streaming || !api.signedIn()) return;
    refreshing = true;
    int current = epoch;
    jobs.execute(
        () -> {
          Catalog.Home loaded = null;
          List<Game> lib = null;
          JSONObject defs = null;
          Exception primary = null, secondary = null;
          try {
            loaded = catalog.home(force);
          } catch (Exception e) {
            primary = e;
          }
          Catalog.Home first = loaded;
          if (first != null)
            ui.post(
                () -> {
                  if (!destroyed
                      && !streaming
                      && current == epoch
                      && homeList == null
                      && screen.equals("home")) {
                    home = first;
                    homeError = "";
                    showHome();
                  }
                });
          boolean authenticationFailed =
              primary instanceof Api.Failure && ((Api.Failure) primary).code == 401;
          if (!destroyed && !streaming && !authenticationFailed)
            try {
              lib = catalog.library();
            } catch (Exception e) {
              if (primary == null) secondary = e;
            }
          if (!destroyed && !streaming && !authenticationFailed)
            try {
              defs = catalog.definitions();
            } catch (Exception e) {
              if (primary == null && secondary == null) secondary = e;
            }
          Catalog.Home h = loaded;
          List<Game> accountLibrary = lib;
          JSONObject definitionsResult = defs;
          Exception problem = primary, notice = secondary;
          ui.post(
              () -> {
                refreshing = false;
                if (destroyed || streaming || current != epoch) return;
                if (h != null) {
                  home = h;
                  homeError = "";
                } else if (problem != null) homeError = readableError(problem);
                if (accountLibrary != null) library = accountLibrary;
                if (definitionsResult != null) {
                  definitions = definitionsResult;
                  parseGenres();
                }
                // One UI commit per refresh; retain scroll and don't reset focused game rows.
                if (screen.equals("home") && (homeList == null || !homeList.hasFocus())) showHome();
                if (problem != null) error(problem);
                else if (notice != null) error(notice);
              });
        });
  }

  private void parseGenres() {
    genreFilters.clear();
    JSONArray groups = Json.arr(definitions, "filterGroupDefinitions");
    for (int i = 0; i < groups.length(); i++) {
      JSONObject group = groups.optJSONObject(i);
      String id = Json.s(group, "id").toLowerCase(Locale.ROOT);
      if (!id.contains("genre")) continue;
      JSONArray fs = Json.arr(group, "filters");
      for (int f = 0; f < fs.length(); f++) {
        JSONObject item = fs.optJSONObject(f);
        String label = Json.s(item, "label");
        JSONArray ff = Json.arr(item, "filters");
        if (!label.isEmpty() && ff.length() > 0)
          try {
            genreFilters.put(label, new JSONObject(ff.optString(0)));
          } catch (JSONException ignored) {
          }
      }
    }
  }

  private void refreshActiveSessions() {
    if (!visible || streaming || destroyed || activeBusy || !api.signedIn()) return;
    activeBusy = true;
    final int generation = ++activeGeneration;
    final int account = authEpoch;
    final String user = api.userId();
    try {
      jobs.execute(
          () -> {
            List<SessionClient.Active> found = null;
            try {
              found = new SessionClient(api).active(() -> visible && !streaming && !destroyed);
            } catch (Exception ignored) {
            }
            List<SessionClient.Active> result = found;
            ui.post(
                () -> {
                  if (generation != activeGeneration) return;
                  activeBusy = false;
                  if (destroyed
                      || streaming
                      || !visible
                      || account != authEpoch
                      || !user.equals(api.userId())) return;
                  if (result != null) activeSessions = result;
                  updateResumeDetails();
                });
          });
    } catch (RejectedExecutionException ignored) {
      activeBusy = false;
    }
  }

  private SessionClient.Active activeFor(Game game) {
    for (SessionClient.Active active : activeSessions)
      for (Game.Variant variant : game.variants) if (active.appId.equals(variant.id)) return active;
    return null;
  }

  private void updateResumeDetails() {
    boolean found = detailGame != null && activeFor(detailGame) != null;
    if (detailPlay != null) {
      detailPlay.setVisibility(found ? View.GONE : View.VISIBLE);
      detailPlay.setEnabled(!closingSession);
    }
    if (resumeDetails != null) {
      resumeDetails.setVisibility(found ? View.VISIBLE : View.GONE);
      resumeDetails.setEnabled(!closingSession);
    }
    if (closeDetails != null) {
      closeDetails.setVisibility(found ? View.VISIBLE : View.GONE);
      closeDetails.setEnabled(!closingSession);
      closeDetails.setText(closingSession ? l.t("closingSession") : "×  " + l.t("closeSession"));
    }
  }

  private void closeSession(Game game) {
    SessionClient.Active active = activeFor(game);
    if (active == null || closingSession) return;
    new AlertDialog.Builder(this)
        .setTitle(l.t("closeSession"))
        .setMessage(l.t("closeSessionHint"))
        .setNegativeButton(l.t("cancel"), null)
        .setPositiveButton(
            l.t("closeSession"),
            (dialog, which) -> {
              if (closingSession || activeFor(game) == null) return;
              closingSession = true;
              updateResumeDetails();
              int account = authEpoch;
              async(
                  () -> {
                    try {
                      new SessionClient(api).stopActive(active);
                      return (Exception) null;
                    } catch (Exception e) {
                      return e;
                    }
                  },
                  problem -> {
                    closingSession = false;
                    if (account != authEpoch) return;
                    if (problem == null) {
                      activeGeneration++;
                      activeBusy = false;
                      for (Iterator<SessionClient.Active> it = activeSessions.iterator();
                          it.hasNext(); ) if (it.next().seat.id.equals(active.seat.id)) it.remove();
                      toast(l.t("sessionClosed"));
                    }
                    updateResumeDetails();
                    refreshActiveSessions();
                    if (problem != null) error(problem);
                  });
            })
        .show();
  }

  private void resume(SessionClient.Active active, Game game) {
    for (Game.Variant variant : game.variants)
      if (variant.id.equals(active.appId)) {
        launch(game, variant, active);
        return;
      }
  }

  private void showHome() {
    if (content == null) return;
    clear("home");
    if (home == null && !refreshing && homeError.isEmpty()) refresh(false);
    if (home == null) {
      empty(homeError.isEmpty() ? l.t("loading") : homeError, l.t("retry"), () -> refresh(true));
      return;
    }
    List<Catalog.Shelf> shelves = HomeSections.build(library, home.shelves, l.t("library"));
    List<String> genres = new ArrayList<>(genreFilters.keySet());
    if (genres.isEmpty()) {
      Set<String> names = new TreeSet<>();
      for (Catalog.Shelf shelf : shelves) for (Game g : shelf.games) names.addAll(g.genres);
      genres.addAll(names);
    }
    homeList = new RecyclerView(this);
    LinearLayoutManager manager = new LinearLayoutManager(this);
    manager.setInitialPrefetchItemCount(1);
    homeList.setLayoutManager(manager);
    homeList.setItemAnimator(null);
    homeList.setItemViewCacheSize(1);
    homeList.setClipToPadding(false);
    homeList.setAdapter(
        new HomeAdapter(
            this,
            images,
            l,
            shelves,
            home.banners,
            genres,
            shelfScroll,
            new HomeAdapter.Actions() {
              public void game(Game g) {
                details(g);
              }

              public void all() {
                showGrid(l.t("all"), false, "", null);
              }

              public void genre(String name) {
                JSONObject filter = genreFilters.get(name);
                if (filter != null) showGrid(name, false, "", filter);
                else toast(l.t("refresh"));
              }
            }));
    content.addView(homeList, new FrameLayout.LayoutParams(-1, -1));
    Ui.enter(homeList);
    if (homeScroll != null) homeList.getLayoutManager().onRestoreInstanceState(homeScroll);
  }

  private void empty(String title, String action, Runnable run) {
    LinearLayout col = Ui.column(this);
    col.setGravity(Gravity.CENTER);
    col.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
    col.addView(Ui.title(this, title, 24));
    Ui.gap(col, 22);
    col.addView(Ui.button(this, action, run));
    Ui.cancelAnimations(content);
    content.removeAllViews();
    content.addView(col, new FrameLayout.LayoutParams(-1, -1));
  }

  private final class GridCards extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    final List<Game> games;
    final Runnable more;
    boolean hasMore;

    GridCards(List<Game> games, Runnable more) {
      this.games = games;
      this.more = more;
    }

    @Override
    public int getItemViewType(int p) {
      return p < games.size() ? 0 : 1;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int type) {
      View v =
          type == 0
              ? new GameCard(MainActivity.this)
              : Ui.button(MainActivity.this, l.t("more"), more);
      int card =
          (getResources().getConfiguration().screenWidthDp - 64) / Ui.columns(MainActivity.this);
      if (v instanceof GameCard)
        ((GameCard) v).artwork.getLayoutParams().height = Ui.dp(MainActivity.this, card - 8);
      RecyclerView.LayoutParams lp =
          new RecyclerView.LayoutParams(-1, Ui.dp(MainActivity.this, card + 48));
      lp.setMargins(
          Ui.dp(MainActivity.this, 3),
          Ui.dp(MainActivity.this, 3),
          Ui.dp(MainActivity.this, 8),
          Ui.dp(MainActivity.this, 8));
      v.setLayoutParams(lp);
      return new RecyclerView.ViewHolder(v) {};
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
      if (pos < games.size()) {
        Game g = games.get(pos);
        h.itemView.setId(pos == 0 ? R.id.grid_first : View.NO_ID);
        if (pos < Ui.columns(MainActivity.this))
          h.itemView.setNextFocusUpId(
              screen.equals("library") ? R.id.nav_library : R.id.nav_catalog);
        ((GameCard) h.itemView).bind(g, images, () -> details(g));
      }
    }

    @Override
    public int getItemCount() {
      return games.size() + (hasMore ? 1 : 0);
    }
  }

  private void showGrid(String title, boolean mine, String query, JSONObject filter) {
    clear(mine ? "library" : "catalog");
    LinearLayout col = Ui.column(this);
    col.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
    LinearLayout toolbar = Ui.row(this);
    TextView heading = Ui.title(this, title, 23);
    toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    col.addView(toolbar);
    Ui.gap(col, 12);
    List<Game> games = new ArrayList<>();
    RecyclerView grid = new RecyclerView(this);
    grid.setLayoutManager(new GridLayoutManager(this, Ui.columns(this)));
    grid.setItemAnimator(null);
    grid.setItemViewCacheSize(2);
    col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(col);
    Ui.enter(col);
    String[] sort = {"sortName:ASC"}, cursor = {""};
    boolean[] busy = {false};
    int generation = gridEpoch;
    final GridCards[] adapter = new GridCards[1];
    Runnable load =
        new Runnable() {
          public void run() {
            if (busy[0]) return;
            busy[0] = true;
            async(
                () -> {
                  try {
                    return catalog.browse(cursor[0], query, sort[0], mine, filter);
                  } finally {
                    ui.post(() -> busy[0] = false);
                  }
                },
                page -> {
                  busy[0] = false;
                  if (generation != gridEpoch) return;
                  int old = games.size();
                  Set<String> ids = new HashSet<>();
                  for (Game g : games) ids.add(g.id);
                  for (Game g : page.games) if (ids.add(g.id)) games.add(g);
                  cursor[0] = page.cursor;
                  adapter[0].hasMore = page.more;
                  adapter[0].notifyDataSetChanged();
                  heading.setText(title + "  ·  " + games.size() + " / " + page.total);
                  if (old == 0 && games.isEmpty()) toast("0");
                });
          }
        };
    adapter[0] = new GridCards(games, load);
    grid.setAdapter(adapter[0]);
    grid.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView rv, int dx, int dy) {
            if (dy > 0
                && adapter[0].hasMore
                && ((GridLayoutManager) rv.getLayoutManager()).findLastVisibleItemPosition()
                    >= games.size() - 7) load.run();
          }
        });
    TextView sortButton =
        Ui.button(
            this,
            "A–Z  ↓",
            () -> {
              JSONArray options = Json.arr(definitions, "sortOrderDefinitions");
              List<String> labels = new ArrayList<>(Arrays.asList("A–Z", "Z–A")),
                  values = new ArrayList<>(Arrays.asList("sortName:ASC", "sortName:DESC"));
              for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                String order = Json.s(o, "orderBy");
                if (!order.isEmpty()) {
                  labels.add(Json.s(o, "label"));
                  values.add(order);
                }
              }
              new AlertDialog.Builder(this)
                  .setTitle("A–Z")
                  .setItems(
                      labels.toArray(new String[0]),
                      (d, w) -> {
                        if (busy[0]) return;
                        sort[0] = values.get(w);
                        cursor[0] = "";
                        games.clear();
                        adapter[0].notifyDataSetChanged();
                        load.run();
                      })
                  .show();
            });
    toolbar.addView(sortButton);
    load.run();
  }

  private void searchDialog() {
    EditText text = new EditText(this);
    text.setSingleLine(true);
    text.setHint(l.t("search"));
    new AlertDialog.Builder(this)
        .setTitle(l.t("search"))
        .setView(text)
        .setPositiveButton(
            l.t("search"),
            (d, w) ->
                showGrid(
                    l.t("search"),
                    Navigation.librarySearch(screen),
                    text.getText().toString().trim(),
                    null))
        .setNegativeButton(l.t("cancel"), null)
        .show();
    text.requestFocus();
  }

  private void details(Game game) {
    clear("details");
    FrameLayout page = new FrameLayout(this);
    content.addView(page);
    Ui.enter(page);
    ImageView backdrop = new ImageView(this);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    page.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    images.load(backdrop, game.hero, 1100, 620);
    View fade = new View(this);
    fade.setBackground(
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {Ui.BG, 0xf2070a0e, 0x50070a0e}));
    page.addView(fade, new FrameLayout.LayoutParams(-1, -1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    page.addView(scroll);
    LinearLayout col = Ui.column(this);
    col.setPadding(Ui.dp(this, 30), Ui.dp(this, 22), Ui.dp(this, 30), Ui.dp(this, 28));
    scroll.addView(
        col,
        new ScrollView.LayoutParams(
            Ui.dp(this, Math.min(500, getResources().getConfiguration().screenWidthDp / 2)), -2));
    col.addView(Ui.button(this, "←  " + l.t("home"), this::showHome), Ui.lp(this, 200, 44));
    Ui.gap(col, 14);
    col.addView(Ui.text(this, l.t("details").toUpperCase(settings.locale()), 11, Ui.ACCENT));
    Ui.gap(col, 10);
    col.addView(Ui.title(this, game.title, 26));
    Ui.gap(col, 10);
    col.addView(Ui.text(this, Json.join("  ·  ", game.genres), 13, Ui.MUTED));
    Ui.gap(col, 14);
    if (!game.description.isEmpty()) col.addView(Ui.text(this, game.description, 12, Ui.MUTED));
    Ui.gap(col, 12);
    detailGame = game;
    final Game.Variant[] chosen = {game.selected()};
    TextView ownership = Ui.text(this, "", 14, Ui.MUTED);
    col.addView(ownership);
    Ui.gap(col, 12);
    TextView store =
        Ui.button(
            this, l.t("store") + ": " + (chosen[0] == null ? "—" : chosen[0].store), () -> {});
    col.addView(store);
    store.setOnClickListener(
        v -> {
          String[] stores = new String[game.variants.size()];
          for (int i = 0; i < stores.length; i++) stores[i] = game.variants.get(i).store;
          new AlertDialog.Builder(this)
              .setTitle(l.t("store"))
              .setItems(
                  stores,
                  (d, w) -> {
                    chosen[0] = game.variants.get(w);
                    store.setText(l.t("store") + ": " + chosen[0].store);
                    ownership.setText(
                        chosen[0].owned
                            ? l.t("owned")
                            : chosen[0].free ? l.t("free") : l.t("notOwned"));
                  })
              .show();
        });
    ownership.setText(
        chosen[0] != null && chosen[0].owned
            ? l.t("owned")
            : chosen[0] != null && chosen[0].free ? l.t("free") : l.t("notOwned"));
    Ui.gap(col, 12);
    TextView play =
        Ui.button(
            this,
            "▶  " + l.t("play"),
            () -> {
              Game.Variant variant = chosen[0];
              if (variant == null) {
                toast(l.t("unavailable"));
                return;
              }
              if (!variant.playable()) {
                toast(variant.status);
                return;
              }
              if (!variant.owned && !variant.free) {
                new AlertDialog.Builder(this)
                    .setMessage(l.t("notOwned"))
                    .setPositiveButton(l.t("markOwned"), (d, w) -> mark(game, variant, true))
                    .setNeutralButton(l.t("store"), (d, w) -> openWeb(variant.url, l.t("store")))
                    .setNegativeButton(l.t("back"), null)
                    .show();
                return;
              }
              launch(game, variant);
            });
    Ui.primary(play);
    detailPlay = play;
    col.addView(play);
    resumeDetails =
        Ui.button(
            this,
            "▶  " + l.t("resumeSession"),
            () -> {
              SessionClient.Active active = activeFor(game);
              if (active != null) resume(active, game);
              else refreshActiveSessions();
            });
    Ui.primary(resumeDetails);
    col.addView(resumeDetails);
    Ui.gap(col, 6);
    closeDetails = Ui.button(this, "×  " + l.t("closeSession"), () -> closeSession(game));
    closeDetails.setTextColor(0xffffb6b6);
    col.addView(closeDetails);
    updateResumeDetails();
    refreshActiveSessions();
    Ui.gap(col, 10);
    col.addView(
        Ui.button(
            this,
            l.t("markOwned") + " / " + l.t("removeOwned"),
            () -> {
              if (chosen[0] != null) mark(game, chosen[0], !chosen[0].owned);
            }));
    Ui.gap(col, 10);
    col.addView(
        Ui.button(
            this,
            l.t("store") + "  ↗",
            () -> {
              if (chosen[0] != null) openWeb(chosen[0].url, l.t("store"));
            }));
  }

  private void mark(Game game, Game.Variant variant, boolean owned) {
    async(
        () -> {
          catalog.owned(variant, owned);
          return catalog.library();
        },
        result -> {
          library = result;
          toast("✓");
          for (Game g : library)
            if (g.id.equals(game.id)) {
              details(g);
              return;
            }
          showHome();
        });
  }

  private void launch(Game game, Game.Variant variant) {
    SessionClient.Active active = activeFor(game);
    launch(game, variant, active != null && active.appId.equals(variant.id) ? active : null);
  }

  private void launch(Game game, Game.Variant variant, SessionClient.Active active) {
    if (closingSession) {
      toast(l.t("loading"));
      return;
    }
    if (active == null
        && settings.engine().equals("native")
        && DecoderFactory.candidates(
                settings.codec(), settings.width(), settings.height(), settings.fps())
            .isEmpty()) {
      new AlertDialog.Builder(this)
          .setMessage(DecoderFactory.report(settings))
          .setPositiveButton(l.t("settings"), (d, w) -> showSettings())
          .setNegativeButton(l.t("cancel"), null)
          .show();
      return;
    }
    // Only identifiers/metadata cross the activity boundary; never account tokens.
    Intent i =
        new Intent(this, StreamActivity.class)
            .putExtra("game", game.raw.toString())
            .putExtra("variant", variant.id);
    if (active != null)
      i.putExtra("resumeId", active.seat.id).putExtra("resumeBase", active.seat.base);
    streaming = true;
    startActivity(i);
  }

  private ScrollView settingsPage(String title) {
    clear("settings");
    FrameLayout page = new FrameLayout(this);
    content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    int rail = Math.max(170, Math.min(240, getResources().getConfiguration().screenWidthDp / 5));
    LinearLayout intro = Ui.column(this);
    intro.setPadding(Ui.dp(this, 12), Ui.dp(this, 25), Ui.dp(this, 20), Ui.dp(this, 22));
    TextView eyebrow = Ui.text(this, "BGeforce NOW TV", 10, Ui.ACCENT);
    eyebrow.setLetterSpacing(.15f);
    intro.addView(eyebrow);
    Ui.gap(intro, 14);
    intro.addView(Ui.title(this, title, 25));
    Ui.gap(intro, 16);
    intro.addView(Ui.text(this, l.t("settingsHint"), 13, Ui.MUTED));
    Ui.gap(intro, 26);
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.ic_logo);
    intro.addView(mark, Ui.lp(this, 80, 80));
    Ui.gap(intro, 14);
    intro.addView(Ui.text(this, api.userName(), 11, Ui.MUTED));
    Ui.gap(intro, 8);
    intro.addView(Ui.title(this, settings.width() + " × " + settings.height(), 18));
    intro.addView(Ui.text(this, settings.fps() + " FPS  ·  " + settings.codec(), 12, Ui.MUTED));
    Ui.gap(intro, 6);
    intro.addView(
        Ui.text(
            this,
            settings.kbps() / 1000
                + " Mbps  ·  "
                + l.t(settings.engine().equals("native") ? "native" : "web"),
            11,
            Ui.ACCENT));
    FrameLayout.LayoutParams ip =
        new FrameLayout.LayoutParams(Ui.dp(this, rail), -1, Gravity.START);
    page.addView(intro, ip);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout box = Ui.column(this);
    box.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 28));
    scroll.addView(box, new ScrollView.LayoutParams(-1, -2));
    FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -1);
    sp.setMarginStart(Ui.dp(this, rail));
    page.addView(scroll, sp);
    Ui.enter(page);
    return scroll;
  }

  private void section(LinearLayout box, String title) {
    Ui.gap(box, 20);
    TextView t = Ui.text(this, title.toUpperCase(settings.locale()), 11, Ui.ACCENT);
    box.addView(t);
    Ui.gap(box, 10);
  }

  private void setting(LinearLayout box, String title, String value, Runnable click) {
    LinearLayout row = Ui.row(this);
    if (box.findViewById(R.id.settings_first) == null) row.setId(R.id.settings_first);
    row.setFocusable(true);
    row.setClickable(true);
    Ui.focus(row);
    row.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
    TextView label = Ui.title(this, title, 15);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView selected = Ui.text(this, value, 13, Ui.MUTED);
    selected.setGravity(Gravity.END);
    selected.setMaxLines(2);
    selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(selected, new LinearLayout.LayoutParams(0, -2, 1));
    TextView arrow = Ui.text(this, "  ›", 20, Ui.ACCENT);
    row.addView(arrow);
    row.setOnClickListener(v -> click.run());
    LinearLayout.LayoutParams p = Ui.lp(this, -1, -2);
    p.bottomMargin = Ui.dp(this, 8);
    box.addView(row, p);
  }

  private void choose(String title, String[] labels, String[] values, String key) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            labels,
            (d, w) -> {
              if (key.equals("performance")
                  && values[w].equals("fullhd")
                  && DecoderFactory.candidates("H264", 1920, 1080, 60).isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("1080p · 60 FPS")
                    .setMessage(l.t("fullhdUnavailable"))
                    .setPositiveButton(l.t("back"), null)
                    .show();
                return;
              }
              settings.set(key, values[w]);
              if (key.equals("language")) {
                l = new L(settings.locale());
                home = null;
                library.clear();
                definitions = new JSONObject();
                genreFilters.clear();
                catalog = new Catalog(this, api);
                epoch++;
                shell();
                showSettings();
                refresh(true);
              } else showSettings();
            })
        .setNegativeButton(l.t("cancel"), null)
        .show();
  }

  private void number(String key, String title, int[] values, String suffix) {
    String[] names = new String[values.length];
    for (int i = 0; i < values.length; i++) names[i] = values[i] + suffix;
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            names,
            (d, w) -> {
              settings.set(key, values[w]);
              showSettings();
            })
        .setNegativeButton(l.t("cancel"), null)
        .show();
  }

  private void toggle(LinearLayout box, String title, String key, boolean defaultValue) {
    setting(
        box,
        title,
        settings.flag(key, defaultValue) ? "● ON" : "○ OFF",
        () -> {
          settings.set(key, !settings.flag(key, defaultValue));
          showSettings();
        });
  }

  private void showSettings() {
    refresh(false);
    ScrollView scroll = settingsPage(l.t("settings"));
    LinearLayout box = (LinearLayout) scroll.getChildAt(0);
    section(box, l.t("streaming"));
    String mode = settings.performanceMode();
    setting(
        box,
        l.t("performance"),
        l.t(mode),
        () ->
            choose(
                l.t("performance"),
                new String[] {
                  l.t("balanced"), l.t("fullhd"), l.t("light"), l.t("ultra"), l.t("custom")
                },
                new String[] {"balanced", "fullhd", "light", "ultra", "custom"},
                "performance"));
    setting(
        box,
        l.t("engine"),
        l.t(settings.engine().equals("native") ? "native" : "web"),
        () ->
            choose(
                l.t("engine"),
                new String[] {l.t("native"), l.t("web")},
                new String[] {"native", "webview"},
                "engine"));
    setting(
        box,
        l.t("resolution"),
        settings.width() + " × " + settings.height(),
        () -> number("width", l.t("resolution"), new int[] {854, 1280, 1920}, " px"));
    setting(
        box,
        l.t("bitrate"),
        settings.kbps() / 1000 + " Mbps",
        () ->
            number(
                "bitrate",
                l.t("bitrate"),
                new int[] {3, 4, 5, 8, 10, 15, 20, 30, 40, 50},
                " Mbps"));
    setting(
        box,
        l.t("fps"),
        settings.fps() + "",
        () -> number("fps", l.t("fps"), new int[] {30, 60}, " FPS"));
    setting(
        box,
        l.t("codec"),
        settings.codec(),
        () ->
            choose(
                l.t("codec"),
                new String[] {"H.264", "H.265 / HEVC", "AV1"},
                new String[] {"H264", "H265", "AV1"},
                "codec"));
    setting(box, l.t("server"), settings.get("serverName", l.t("auto")), this::servers);
    setting(box, l.t("network"), "↗", this::servers);
    toggle(box, l.t("stats"), "stats", false);
    section(box, l.t("quality"));
    setting(
        box,
        l.t("decoderPath"),
        l.t(settings.decoderPath()),
        () ->
            choose(
                l.t("decoderPath"),
                new String[] {l.t("compatible"), l.t("fast"), l.t("direct")},
                new String[] {"compatible", "fast", "direct"},
                "decoderPath"));
    toggle(
        box,
        settings.locale().getLanguage().equals("pt")
            ? "Baixa latência · decodificadores avançados"
            : "Low latency · advanced decoders",
        "lowLatency",
        true);
    toggle(box, "Adaptive resolution", "adaptive", false);
    toggle(box, l.t("keyboardMouse"), "keyboardMouse", true);
    toggle(box, l.t("captureMouse"), "captureMouse", false);
    toggle(box, l.t("touchInput"), "touchInput", true);
    setting(
        box,
        "Gamepad dead zone",
        settings.number("deadzone", 12) + "%",
        () ->
            number("deadzone", "Gamepad dead zone", new int[] {0, 5, 10, 12, 15, 20, 25, 30}, "%"));
    setting(
        box,
        l.t("microphone"),
        settings.flag("mic", false) ? "● ON" : "○ OFF",
        () -> {
          if (settings.flag("mic", false)) {
            settings.set("mic", false);
            showSettings();
          } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 101);
          else {
            settings.set("mic", true);
            showSettings();
          }
        });
    box.addView(Ui.text(this, "(" + l.t("microphoneHint") + ")", 12, Ui.MUTED));
    Ui.gap(box, 12);
    setting(
        box,
        l.t("codec"),
        l.t("details"),
        () ->
            new AlertDialog.Builder(this)
                .setTitle(l.t("codec"))
                .setMessage(DecoderFactory.report(settings))
                .setPositiveButton(l.t("back"), null)
                .show());
    section(box, l.t("account"));
    setting(
        box,
        l.t("connections"),
        "Steam · Epic Games · Ubisoft · Xbox · Battle.net",
        this::connections);
    setting(
        box,
        l.t("language"),
        settings.get("language", "system"),
        () ->
            choose(
                l.t("language"),
                new String[] {
                  l.t("system"),
                  "Português (Brasil)",
                  "English",
                  "Español",
                  "Français",
                  "Deutsch",
                  "Italiano",
                  "中文",
                  "日本語",
                  "한국어",
                  "Русский",
                  "العربية",
                  "हिन्दी",
                  "Türkçe",
                  "Bahasa Indonesia"
                },
                new String[] {
                  "system", "pt-BR", "en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "zh-CN", "ja-JP",
                  "ko-KR", "ru-RU", "ar-SA", "hi-IN", "tr-TR", "id-ID"
                },
                "language"));
    setting(
        box,
        l.t("refresh"),
        "↻",
        () -> {
          refresh(true);
          showHome();
        });
    setting(
        box,
        l.t("logout"),
        "↗",
        () ->
            new AlertDialog.Builder(this)
                .setTitle(l.t("logout"))
                .setPositiveButton(l.t("logout"), (d, w) -> logout())
                .setNegativeButton(l.t("cancel"), null)
                .show());
    section(box, l.t("about"));
    box.addView(
        Ui.text(
            this,
            "BGeforce Now TV "
                + BuildConfig.VERSION_NAME
                + " · Cliente independente\n"
                + "Independent community client · OpenNOW protocol (MIT)\n"
                + "GeForce NOW / NVIDIA are their owners’ trademarks.\n"
                + "720p60 · H.264 · 8 Mbps is the default profile. Actual frame rate"
                + " depends on the TV, network, service and game. Native Android WebRTC differs"
                + " from the desktop NVST engine.",
            12,
            Ui.MUTED));
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 101) {
      settings.set("mic", results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
      showSettings();
    }
  }

  private void connections() {
    ScrollView scroll = settingsPage(l.t("connections"));
    screen = "connections";
    LinearLayout box = (LinearLayout) scroll.getChildAt(0);
    TextView msg = Ui.text(this, l.t("loading"), 14, Ui.MUTED);
    box.addView(msg);
    async(
        catalog::connectors,
        data -> {
          if (!screen.equals("connections")) return;
          box.removeView(msg);
          JSONArray defs = Json.arr(data, "appStoreDefinitions"),
              linked = Json.arr(Json.obj(data, "userAccount"), "storesData");
          Map<String, JSONObject> links = new HashMap<>();
          for (int i = 0; i < linked.length(); i++) {
            JSONObject j = linked.optJSONObject(i);
            links.put(Json.s(j, "store"), Json.obj(j, "accountLinkingData"));
          }
          for (int i = 0; i < defs.length(); i++) {
            JSONObject store = defs.optJSONObject(i);
            String id = Json.s(store, "store"), label = Json.first(store, "label", "store");
            boolean supported = Json.obj(store, "accountLinkingMetadata").optBoolean("isSupported");
            JSONObject connection = links.get(id);
            boolean connected =
                connection != null
                    && !Json.first(connection, "userIdentifier", "userDisplayName").isEmpty();
            String value =
                connected ? "✓ " + Json.s(connection, "userDisplayName") : supported ? "＋" : "—";
            setting(
                box,
                label,
                value,
                () -> {
                  if (!supported) {
                    new AlertDialog.Builder(this)
                        .setTitle(label)
                        .setMessage(l.t("unavailable"))
                        .setPositiveButton(l.t("back"), null)
                        .show();
                    return;
                  }
                  new AlertDialog.Builder(this)
                      .setTitle(label)
                      .setItems(
                          new String[] {"Celular · QR Code", "Nesta TV · site"},
                          (dialog, choice) ->
                              async(
                                  () -> catalog.connectUrl(id),
                                  url -> {
                                    if (choice == 0) qrPage(url, label);
                                    else openWeb(url, label);
                                  }))
                      .show();
                });
          }
          setting(
              box,
              l.t("refresh"),
              "↻",
              () -> {
                connections();
                refresh(true);
              });
        });
  }

  private void servers() {
    ScrollView scroll = settingsPage(l.t("network"));
    screen = "network";
    LinearLayout box = (LinearLayout) scroll.getChildAt(0);
    TextView help =
        Ui.text(
            this,
            "TCP connect time ≠ streaming RTT. " + l.t("queue") + ": " + l.t("unavailable"),
            12,
            Ui.MUTED);
    box.addView(help);
    TextView loadingServers = Ui.text(this, l.t("loading"), 16, Ui.ACCENT);
    box.addView(loadingServers);
    Ui.gap(box, 16);
    setting(
        box,
        l.t("server"),
        l.t("auto"),
        () -> {
          settings.prefs.edit().remove("serverUrl").remove("serverName").apply();
          showSettings();
        });
    async(
        () -> {
          JSONObject info = api.gfn(api.base() + "v2/serverInfo", "GET", null);
          JSONArray meta = Json.arr(info, "metaData");
          List<ServerProbe> probes = new ArrayList<>();
          for (int i = 0; i < meta.length(); i++) {
            JSONObject entry = meta.optJSONObject(i);
            String key = Json.s(entry, "key"), url = Json.s(entry, "value");
            if (key.startsWith("gfn-") || !url.startsWith("https://")) continue;
            try {
              Api.validateServiceUrl(url);
              probes.add(new ServerProbe(key, url));
            } catch (IOException ignored) {
            }
          }
          JSONObject estimates = new JSONObject();
          try {
            JSONObject response =
                api.request(api.standard("https://api.printedwaste.com/gfn/queue/").build());
            if (response.optBoolean("status")) estimates = Json.obj(response, "data");
          } catch (IOException ignored) {
          }
          for (ServerProbe probe : probes) {
            probe.test();
            String hostname = URI.create(probe.url).getHost().split("\\.")[0];
            JSONObject q = estimates.optJSONObject(hostname.toUpperCase(Locale.ROOT));
            if (q == null) q = estimates.optJSONObject(probe.name);
            if (q != null && q.has("QueuePosition")) {
              long updated = q.optLong("Last Updated", 0);
              if (updated < 100000000000L) updated *= 1000;
              if (Math.abs(System.currentTimeMillis() - updated) < 10 * 60 * 1000) {
                probe.estimate = q.optInt("QueuePosition", -1);
                probe.updated = updated;
              }
            }
          }
          Collections.sort(
              probes,
              (a, b) ->
                  Long.compare(
                      a.millis < 0 ? Long.MAX_VALUE : a.millis,
                      b.millis < 0 ? Long.MAX_VALUE : b.millis));
          return probes;
        },
        probes -> {
          box.removeView(loadingServers);
          for (int i = 0; i < probes.size(); i++) {
            ServerProbe p = probes.get(i);
            setting(
                box,
                (i == 0 && p.millis >= 0 ? "★  " : "") + p.name,
                p.millis < 0
                    ? l.t("unavailable")
                    : p.millis
                        + " ms (TCP)  ·  "
                        + l.t("queue")
                        + ": "
                        + (p.estimate < 0 ? "—" : "~" + p.estimate + " (Free · PrintedWaste)"),
                () -> {
                  settings.set("serverUrl", p.url);
                  settings.set("serverName", p.name);
                  showSettings();
                });
          }
          if (probes.isEmpty()) box.addView(Ui.text(this, l.t("unavailable"), 14, Ui.MUTED));
        });
  }

  static final class ServerProbe {
    final String name, url;
    long millis = -1, updated;
    int estimate = -1;

    ServerProbe(String n, String u) {
      name = n;
      url = u;
    }

    void test() {
      try {
        URI uri = URI.create(url);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
          long start = System.nanoTime();
          try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort()),
                1200);
            best = Math.min(best, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
          }
        }
        millis = best;
      } catch (Exception ignored) {
      }
    }
  }

  private void logout() {
    activeGeneration++;
    activeBusy = false;
    activeSessions.clear();
    settings.prefs.edit().remove("resumeGame").remove("resumeGameAccount").apply();
    authEpoch++;
    epoch++;
    gridEpoch++;
    NetworkCleanup.cancelCalls(api.http);
    api.logout();
    android.webkit.CookieManager.getInstance().removeAllCookies(null);
    android.webkit.WebStorage.getInstance().deleteAllData();
    homeScroll = null;
    shelfScroll.clear();
    homeError = "";
    catalog.clearCache();
    images.trim();
    home = null;
    library.clear();
    definitions = new JSONObject();
    genreFilters.clear();
    challenge = null;
    ui.removeCallbacks(poll);
    settings.prefs.edit().remove("serverUrl").remove("serverName").apply();
    login();
  }

  private void login() {
    authEpoch++;
    challenge = null;
    l = new L(settings.locale());
    root = new FrameLayout(this);
    root.setBackground(
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[] {0xff111b2a, Ui.BG, 0xff102127}));
    setContentView(root);
    LinearLayout row = Ui.row(this);
    row.setPadding(Ui.dp(this, 70), Ui.dp(this, 35), Ui.dp(this, 70), Ui.dp(this, 35));
    root.addView(row, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout text = Ui.column(this);
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_logo);
    text.addView(logo, Ui.lp(this, 88, 88));
    Ui.gap(text, 14);
    text.addView(Ui.title(this, "BGeforce Now TV", 32));
    Ui.gap(text, 20);
    text.addView(Ui.title(this, l.t("login"), 22));
    Ui.gap(text, 14);
    text.addView(Ui.text(this, l.t("loginHint"), 16, Ui.MUTED));
    Ui.gap(text, 22);
    loginState = Ui.text(this, l.t("loading"), 14, Ui.ACCENT);
    text.addView(loginState);
    LinearLayout code = Ui.column(this);
    code.setGravity(Gravity.CENTER);
    code.setBackground(Ui.bg(0xff151e2a, Ui.dp(this, 20)));
    code.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
    LinearLayout.LayoutParams lp = Ui.lp(this, 280, -2);
    lp.leftMargin = Ui.dp(this, 50);
    row.addView(code, lp);
    TextView regenerate = Ui.button(this, l.t("retry"), this::login);
    code.addView(regenerate);
    int attempt = authEpoch;
    async(
        () -> api.authorize(null),
        value -> {
          if (attempt != authEpoch) return;
          challenge = value;
          challengeExpires = System.currentTimeMillis() + value.optLong("expires_in", 600) * 1000L;
          authInterval = Math.max(5, value.optInt("interval", 5));
          code.removeAllViews();
          String uri = Json.first(value, "verification_uri_complete", "verification_uri");
          ImageView qr = new ImageView(this);
          qr.setImageBitmap(qr(uri));
          code.addView(qr, Ui.lp(this, 218, 218));
          Ui.gap(code, 14);
          TextView pin = Ui.title(this, Json.s(value, "user_code"), 30);
          pin.setLetterSpacing(.12f);
          code.addView(pin);
          Ui.gap(code, 10);
          TextView url = Ui.text(this, Json.s(value, "verification_uri"), 12, Ui.MUTED);
          url.setGravity(Gravity.CENTER);
          code.addView(url);
          Ui.gap(code, 12);
          code.addView(Ui.button(this, l.t("retry"), this::login));
          loginState.setText(l.t("loading"));
          ui.postDelayed(poll, authInterval * 1000L);
        });
  }

  private void pollLogin() {
    if (challenge == null || api.signedIn() || destroyed || !visible) return;
    if (System.currentTimeMillis() >= challengeExpires) {
      loginState.setText(l.t("retry"));
      return;
    }
    int attempt = authEpoch;
    String deviceCode = Json.s(challenge, "device_code");
    jobs.execute(
        () -> {
          try {
            JSONObject tokens = api.pollCode(deviceCode);
            if (attempt != authEpoch) return;
            api.accept(tokens);
            ui.post(
                () -> {
                  if (!destroyed && attempt == authEpoch) {
                    challenge = null;
                    shell();
                    showHome();
                    refresh(true);
                  }
                });
          } catch (Api.Failure e) {
            String reason = Json.s(e.payload, "error");
            ui.post(
                () -> {
                  if (attempt != authEpoch || destroyed) return;
                  if (reason.equals("slow_down")) authInterval += 5;
                  if (reason.equals("authorization_pending") || reason.equals("slow_down"))
                    ui.postDelayed(poll, authInterval * 1000L);
                  else loginState.setText(reason + " · " + l.t("retry"));
                });
          } catch (Exception e) {
            ui.post(
                () -> {
                  if (attempt == authEpoch && !destroyed) {
                    loginState.setText(e.getClass().getSimpleName() + " · " + l.t("retry"));
                    ui.postDelayed(poll, Math.max(10, authInterval) * 1000L);
                  }
                });
          }
        });
  }

  private Bitmap qr(String value) {
    try {
      BitMatrix m =
          new MultiFormatWriter()
              .encode(
                  value,
                  BarcodeFormat.QR_CODE,
                  512,
                  512,
                  Collections.singletonMap(EncodeHintType.MARGIN, 3));
      int[] pixels = new int[512 * 512];
      for (int y = 0; y < 512; y++)
        for (int x = 0; x < 512; x++) pixels[y * 512 + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
      return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.RGB_565);
    } catch (Exception e) {
      return Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
    }
  }

  private void qrPage(String url, String title) {
    if (url == null || !url.startsWith("https://")) {
      toast(l.t("unavailable"));
      return;
    }
    LinearLayout col = Ui.column(this);
    col.setPadding(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));
    col.setGravity(Gravity.CENTER);
    ImageView q = new ImageView(this);
    q.setImageBitmap(qr(url));
    col.addView(q, Ui.lp(this, 230, 230));
    Ui.gap(col, 12);
    TextView link = Ui.text(this, url, 12, Ui.MUTED);
    link.setTextIsSelectable(true);
    col.addView(link);
    col.addView(
        Ui.text(
            this,
            "Leia o QR Code no celular. A loja pode pedir seu próprio código de segurança; este"
                + " vínculo não fornece um código curto separado.",
            13,
            Ui.MUTED));
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(col)
        .setPositiveButton(
            l.t("back"),
            (d, w) -> {
              refresh(true);
            })
        .setNeutralButton(
            l.t("refresh"),
            (d, w) -> {
              refresh(true);
              connections();
            })
        .show();
  }

  private String readableError(Exception e) {
    if (e instanceof Api.Failure && ((Api.Failure) e).code == 401)
      return settings.locale().getLanguage().equals("pt")
          ? "A sessão foi recusada após a renovação. Tente novamente ou entre na conta novamente em"
              + " Configurações."
          : "The session was rejected after renewal. Retry or sign in again from Settings.";
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private void error(Exception e) {
    String message = readableError(e);
    toast(message);
    if (status != null) status.setText(l.t("retry"));
  }

  private void openWeb(String url, String title) {
    if (url == null || !url.startsWith("https://")) {
      toast(l.t("unavailable"));
      return;
    }
    webReturning = screen.equals("connections");
    startActivity(
        new Intent(this, AccountWebActivity.class).putExtra("url", url).putExtra("title", title));
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onBackPressed() {
    if (api.signedIn() && !screen.equals("home")) showHome();
    else super.onBackPressed();
  }
}
