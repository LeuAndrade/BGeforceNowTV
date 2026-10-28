package com.bgeforce.tv;

import java.util.*;
import org.json.*;

public final class Game {
  public final JSONObject raw;
  public final String id, title, image, cover, hero, publisher, description;
  public final List<String> genres = new ArrayList<>();
  public final List<Variant> variants = new ArrayList<>();

  public Game(JSONObject j) {
    raw = j;
    id = Json.s(j, "id");
    title = Json.s(j, "title");
    publisher = Json.s(j, "publisherName");
    description = Json.first(j, "shortDescription", "longDescription");
    JSONObject im = Json.obj(j, "images");
    image = Json.first(im, "TV_BANNER", "KEY_ART", "GAME_BOX_ART", "KEY_IMAGE");
    cover = Json.first(im, "GAME_BOX_ART", "KEY_ART", "KEY_IMAGE", "TV_BANNER");
    hero = Json.first(im, "MARQUEE_HERO_IMAGE", "HERO_IMAGE", "TV_BANNER", "KEY_ART");
    JSONArray gg = Json.arr(j, "genres");
    for (int i = 0; i < gg.length(); i++) {
      Object v = gg.opt(i);
      String s =
          v instanceof JSONObject
              ? Json.first((JSONObject) v, "name", "label", "title")
              : String.valueOf(v);
      if (!s.isEmpty()) genres.add(s);
    }
    JSONArray vv = Json.arr(j, "variants");
    for (int i = 0; i < vv.length(); i++) {
      JSONObject v = vv.optJSONObject(i);
      if (v != null) variants.add(new Variant(v));
    }
  }

  public Variant selected() {
    for (Variant v : variants) if (v.selected) return v;
    for (Variant v : variants) if (v.owned) return v;
    return variants.isEmpty() ? null : variants.get(0);
  }

  public boolean owned() {
    for (Variant v : variants) if (v.owned) return true;
    return false;
  }

  public boolean free() {
    for (Variant v : variants) if (v.free) return true;
    return false;
  }

  public String lastPlayed() {
    String latest = "";
    for (Variant v : variants) if (v.lastPlayed.compareTo(latest) > 0) latest = v.lastPlayed;
    return latest;
  }

  public static final class Variant {
    public final String id, store, url, status, lastPlayed;
    public final boolean owned, selected, free;

    Variant(JSONObject v) {
      id = Json.s(v, "id");
      url = Json.s(v, "storeUrl");
      String source = Json.first(v, "appStore", "store");
      if (source.isEmpty()) {
        String lower = url.toLowerCase(Locale.ROOT);
        source =
            lower.contains("steam")
                ? "STEAM"
                : lower.contains("epicgames")
                    ? "EPIC"
                    : lower.contains("ubisoft")
                        ? "UBISOFT"
                        : lower.contains("battle.net")
                            ? "BATTLENET"
                            : lower.contains("xbox") || lower.contains("microsoft") ? "XBOX" : "PC";
      }
      store = source;
      JSONObject g = Json.obj(v, "gfn"), lib = Json.obj(g, "library");
      String ownership = Json.s(lib, "status");
      owned =
          ownership.equals("MANUAL")
              || ownership.equals("PLATFORM_SYNC")
              || ownership.equals("IN_LIBRARY");
      selected = lib.optBoolean("selected", false);
      lastPlayed = Json.s(lib, "lastPlayedDate");
      status = Json.s(g, "status");
      free =
          Json.arr(v, "paymentModels").toString().toUpperCase(Locale.ROOT).contains("FREETOPLAY");
    }

    public boolean playable() {
      return status.isEmpty() || status.equals("AVAILABLE") || status.equals("ACTIVE");
    }
  }
}
