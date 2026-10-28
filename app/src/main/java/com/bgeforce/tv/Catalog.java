package com.bgeforce.tv;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.*;
import org.json.*;

public final class Catalog {
  private static final String HASH =
      "46ec15f267a056e7d5e46e629efa929529e5e7542a4850faece90b9f8fa5f810";
  private static final String FIELDS =
      "id title shortDescription publisherName genres images { KEY_ART GAME_BOX_ART TV_BANNER"
          + " HERO_IMAGE MARQUEE_HERO_IMAGE } variants { id appStore storeUrl paymentModels {"
          + " __typename } gfn { status library { status selected lastPlayedDate } } }";
  private final Api api;
  private final Context context;
  private String vpc = "GFN-PC";

  public Catalog(Context c, Api api) {
    context = c.getApplicationContext();
    this.api = api;
  }

  public static final class Shelf {
    public final String id, title;
    public final List<Game> games;

    public Shelf(String id, String title, List<Game> games) {
      this.id = id;
      this.title = title;
      this.games = games;
    }
  }

  public static final class Home {
    public final List<Shelf> shelves = new ArrayList<>();
    public final List<Game> banners = new ArrayList<>();
    public long updated;
  }

  public static final class Page {
    public final List<Game> games = new ArrayList<>();
    public String cursor = "";
    public boolean more;
    public int total;
  }

  public void serverInfo() throws IOException {
    JSONObject info = api.gfn(api.base() + "v2/serverInfo", "GET", null);
    String s = Json.s(Json.obj(info, "requestStatus"), "serverId");
    if (!s.isEmpty()) vpc = s;
  }

  private JSONObject panels(String... names) throws IOException {
    JSONObject variables =
        Json.o(
            "vpcId",
            vpc,
            "locale",
            api.settings.apiLocale(),
            "panelNames",
            Json.a((Object[]) names));
    JSONObject ext = Json.o("persistedQuery", Json.o("sha256Hash", HASH));
    HttpUrl u =
        HttpUrl.get("https://games.geforce.com/graphql")
            .newBuilder()
            .addQueryParameter(
                "requestType", names[0].equals("MARQUEE") ? "panels/Marquee" : "panels/MainV2")
            .addQueryParameter("extensions", ext.toString())
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("huId", Long.toHexString(System.currentTimeMillis()))
            .build();
    Request.Builder r = api.standard(u.toString());
    Headers h = api.headers(api.token());
    for (String n : h.names()) r.header(n, h.get(n));
    JSONObject result;
    try {
      result = api.authenticated(r.build(), "GFNJWT");
    } catch (Api.Failure e) {
      if (e.code != 403) throw e;
      result =
          api.authenticated(
              r.url(u.newBuilder().host("apps.gxn.nvidia.com").build()).build(), "GFNJWT");
    }
    if (Json.arr(result, "errors").length() > 0)
      throw new IOException(
          "Home metadata: " + Json.s(Json.arr(result, "errors").optJSONObject(0), "message"));
    return result;
  }

  public Home home(boolean refresh) throws IOException {
    String key = "home-" + scope() + ".json";
    JSONObject saved = load(key);
    if (!refresh
        && saved != null
        && System.currentTimeMillis() - saved.optLong("savedAt") < 15 * 60 * 1000)
      return parseHome(saved);
    try {
      serverInfo();
    } catch (IOException ignored) {
      /* Catalog requests can use GFN-PC if the broker is temporarily unavailable. */
    }
    JSONObject main = panels("MAIN");
    JSONObject marquee;
    try {
      marquee = panels("MARQUEE");
    } catch (IOException e) {
      marquee = new JSONObject();
    }
    JSONObject root =
        Json.o("main", main, "marquee", marquee, "savedAt", System.currentTimeMillis());
    save(key, root);
    return parseHome(root);
  }

  public Home cachedHome() {
    JSONObject j = load("home-" + scope() + ".json");
    return j == null ? null : parseHome(j);
  }

  static Home parseHome(JSONObject root) {
    Home home = new Home();
    home.updated = root.optLong("savedAt");
    JSONArray panels = Json.arr(Json.obj(Json.obj(root, "main"), "data"), "panels");
    for (int p = 0; p < panels.length(); p++) {
      JSONArray sections = Json.arr(panels.optJSONObject(p), "sections");
      for (int i = 0; i < sections.length(); i++) {
        JSONObject s = sections.optJSONObject(i);
        List<Game> games = gamesFromItems(Json.arr(s, "items"));
        String title = Json.first(s, "title", "id");
        if (!games.isEmpty()) home.shelves.add(new Shelf(Json.s(s, "id"), title, games));
      }
    }
    JSONArray banners = Json.arr(Json.obj(Json.obj(root, "marquee"), "data"), "panels");
    for (int p = 0; p < banners.length(); p++) {
      JSONArray sections = Json.arr(banners.optJSONObject(p), "sections");
      for (int i = 0; i < sections.length(); i++)
        home.banners.addAll(gamesFromItems(Json.arr(sections.optJSONObject(i), "items")));
    }
    if (home.banners.isEmpty() && !home.shelves.isEmpty())
      home.banners.addAll(
          home.shelves.get(0).games.subList(0, Math.min(5, home.shelves.get(0).games.size())));
    return home;
  }

  static List<Game> gamesFromItems(JSONArray items) {
    LinkedHashMap<String, Game> map = new LinkedHashMap<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null) continue;
      JSONObject app = item.optJSONObject("app");
      if (app != null && !Json.s(app, "id").isEmpty()) {
        Game g = new Game(app);
        if (!g.title.isEmpty()) map.put(g.id, g);
      }
      JSONObject nested = item.optJSONObject("game");
      if (nested != null) {
        JSONObject a = nested.optJSONObject("app");
        if (a != null) {
          Game g = new Game(a);
          if (!g.id.isEmpty()) map.put(g.id, g);
        }
      }
    }
    return new ArrayList<>(map.values());
  }

  public Page browse(String cursor, String search, String sort, boolean library)
      throws IOException {
    return browse(cursor, search, sort, library, null);
  }

  public Page browse(
      String cursor, String search, String sort, boolean library, JSONObject selectedFilter)
      throws IOException {
    JSONObject filters =
        library
            ? Json.o(
                "variants",
                Json.o(
                    "gfn", Json.o("library", Json.o("status", Json.o("notEquals", "NOT_OWNED")))))
            : selectedFilter == null ? new JSONObject() : selectedFilter;
    String q =
        "query"
            + " Browse($vpcId:String!,$locale:String!,$cursor:String!,$sort:String!,$filters:AppFilterFields!"
            + (!search.isEmpty() ? ",$search:String!" : "")
            + "){apps(vpcId:$vpcId,language:$locale,first:80,after:$cursor,orderBy:$sort,filters:$filters"
            + (!search.isEmpty() ? ",searchQuery:$search" : "")
            + "){numberSupported pageInfo{hasNextPage endCursor totalCount} items{"
            + FIELDS
            + "}}}";
    JSONObject vars =
        Json.o(
            "vpcId",
            vpc,
            "locale",
            api.settings.apiLocale(),
            "cursor",
            cursor,
            "sort",
            sort,
            "filters",
            filters);
    if (!search.isEmpty())
      try {
        vars.put("search", search);
      } catch (JSONException e) {
        throw new IOException(e);
      }
    JSONObject payload;
    try {
      payload = api.graph(q, vars);
    } catch (IOException e) {
      String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("appstore")
          && (message.contains("represent") || message.contains("serializ")))
        payload = api.graph(q.replace("appStore ", ""), vars);
      else throw e;
    }
    JSONObject data = Json.obj(Json.obj(payload, "data"), "apps");
    JSONArray items = Json.arr(data, "items");
    if (!data.has("pageInfo")) throw new IOException("Catalog returned no pagination state");
    Page page = new Page();
    for (int i = 0; i < items.length(); i++) {
      JSONObject j = items.optJSONObject(i);
      if (j != null) page.games.add(new Game(j));
    }
    JSONObject pi = Json.obj(data, "pageInfo");
    page.more = pi.optBoolean("hasNextPage");
    page.cursor = Json.s(pi, "endCursor");
    page.total = pi.optInt("totalCount", data.optInt("numberSupported"));
    if (page.more && (page.cursor.isEmpty() || page.cursor.equals(cursor)))
      throw new IOException("Catalog cursor did not advance");
    return page;
  }

  public JSONObject definitions() throws IOException {
    return Json.obj(
        api.graph(
            "query Definitions($locale:String!){filterGroupDefinitions(language:$locale){id label"
                + " filters{id label filters}} sortOrderDefinitions(language:$locale){id label"
                + " orderBy}}",
            Json.o("locale", api.settings.apiLocale())),
        "data");
  }

  public List<Game> library() throws IOException {
    List<Game> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String cursor = "";
    long bytes = 0;
    for (int i = 0; i < 250; i++) {
      Page p = browse(cursor, "", "variants.gfn.library.lastPlayedDate:DESC,sortName:ASC", true);
      for (Game g : p.games) bytes += g.raw.toString().length() * 2L;
      if (bytes > 24L * 1024 * 1024)
        throw new IOException("Library exceeds memory budget; old list retained");
      result.addAll(p.games);
      if (!p.more) return result;
      if (!seen.add(p.cursor)) throw new IOException("Library cursor loop");
      cursor = p.cursor;
    }
    throw new IOException("Library exceeds refresh budget; old list retained");
  }

  public void owned(Game.Variant variant, boolean add) throws IOException {
    setOwned(api, variant, add);
  }

  static void setOwned(Api api, Game.Variant variant, boolean add) throws IOException {
    String op = add ? "addOwnedVariant" : "removeOwnedVariant";
    JSONObject r =
        api.graph(
            "mutation Own($cmsId:String!,$locale:String!){"
                + op
                + "(language:$locale,variantId:$cmsId){app{id}}}",
            Json.o("cmsId", variant.id, "locale", api.settings.apiLocale()));
    if (!variant.id.equals(Json.s(Json.obj(Json.obj(Json.obj(r, "data"), op), "app"), "id")))
      throw new IOException("GFN did not confirm the library change");
  }

  public JSONObject connectors() throws IOException {
    return Json.obj(
        api.graph(
            "query Connections($locale:String!){appStoreDefinitions(language:$locale){store label"
                + " accountLinkingMetadata{isSupported isRequired label}}"
                + " userAccount{storesData{store accountLinkingData{userDisplayName userIdentifier"
                + " accountSyncingData{totalNumberOfSyncedGfnGames syncState}}}}}",
            Json.o("locale", api.settings.apiLocale())),
        "data");
  }

  public String connectUrl(String store) throws IOException {
    String platform = store.toUpperCase(Locale.ROOT);
    if (platform.contains("EPIC")) platform = "EPIC";
    if (platform.contains("BATTLE") || platform.equals("BLIZZARD")) platform = "BATTLENET";
    if (platform.contains("UBISOFT")) platform = "UPLAY";
    HttpUrl u =
        HttpUrl.get("https://als.geforcenow.com/v1/login_url")
            .newBuilder()
            .addQueryParameter("platform", platform)
            .addQueryParameter("redirect_uri", "http://localhost:2259/")
            .addQueryParameter("client_id", "gfn-pc")
            .build();
    JSONObject r = api.authenticated(api.standard(u.toString()).build(), "Bearer");
    String url = Json.s(r, "login_url");
    if (url.isEmpty() || !url.startsWith("https://"))
      throw new IOException("No HTTPS account linking URL");
    return url;
  }

  private String scope() {
    return Integer.toHexString((api.userId() + api.base() + api.settings.apiLocale()).hashCode());
  }

  private void save(String name, JSONObject json) throws IOException {
    AtomicFile f = new AtomicFile(new File(context.getCacheDir(), name));
    FileOutputStream out = null;
    try {
      out = f.startWrite();
      out.write(json.toString().getBytes(StandardCharsets.UTF_8));
      f.finishWrite(out);
    } catch (IOException e) {
      if (out != null) f.failWrite(out);
      throw e;
    }
  }

  private JSONObject load(String name) {
    File f = new File(context.getCacheDir(), name);
    if (!f.isFile() || f.length() > 8 * 1024 * 1024) return null;
    try (FileInputStream in = new FileInputStream(f)) {
      return Json.parse(new String(readBytes(in, 8 * 1024 * 1024), StandardCharsets.UTF_8));
    } catch (Exception e) {
      return null;
    }
  }

  public static byte[] readBytes(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      if (out.size() + n > limit) throw new IOException("File exceeds memory budget");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  public void clearCache() {
    File[] ff = context.getCacheDir().listFiles();
    if (ff != null) for (File f : ff) if (f.getName().startsWith("home-")) f.delete();
  }
}
