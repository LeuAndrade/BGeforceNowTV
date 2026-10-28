package com.bgeforce.tv;

import android.content.*;
import java.util.Locale;

public final class Settings {
  public final android.content.SharedPreferences prefs;
  public final boolean constrained;
  private int runtimeWidth, runtimeHeight, runtimeFps;

  Settings forSession(SessionClient.Seat seat) {
    if (!seat.resumed || seat.raw == null) return this;
    org.json.JSONObject request = Json.obj(seat.raw, "sessionRequestData");
    org.json.JSONArray monitors = Json.arr(request, "clientRequestMonitorSettings");
    if (monitors.length() == 0) monitors = Json.arr(seat.raw, "monitorSettings");
    org.json.JSONObject monitor = monitors.optJSONObject(0);
    if (monitor == null) return this;
    int w = monitor.optInt("widthInPixels"),
        h = monitor.optInt("heightInPixels"),
        fps = monitor.optInt("framesPerSecond");
    if (w < 16 || w > 7680 || h < 16 || h > 4320 || fps < 15 || fps > 240) return this;
    Settings effective = new Settings(prefs, constrained);
    effective.runtimeWidth = w;
    effective.runtimeHeight = h;
    effective.runtimeFps = fps;
    return effective;
  }

  public Settings(Context c) {
    this(c.getSharedPreferences("preferences", Context.MODE_PRIVATE), isConstrained(c));
  }

  Settings(SharedPreferences prefs) {
    this(prefs, false);
  }

  Settings(SharedPreferences prefs, boolean constrained) {
    this.prefs = prefs;
    this.constrained = constrained;
  }

  private static boolean isConstrained(Context context) {
    android.app.ActivityManager manager =
        (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
    manager.getMemoryInfo(memory);
    return StreamBudget.constrained(manager.isLowRamDevice(), memory.totalMem);
  }

  public String performanceMode() {
    String selected = get("performance", "balanced");
    return "auto".equals(selected) ? "balanced" : selected;
  }

  private String budget() {
    return StreamBudget.effective(performanceMode(), constrained);
  }

  public boolean directSurface() {
    return "direct".equals(decoderPath());
  }

  public String decoderPath() {
    String path = get("decoderPath", budget().equals("fullhd") ? "direct" : "compatible");
    return "direct".equals(path) || "fast".equals(path) ? path : "compatible";
  }

  public int referenceFrames() {
    return constrained || !budget().equals("custom") ? 2 : 4;
  }

  private SharedPreferences.Editor customEditor() {
    SharedPreferences.Editor editor = prefs.edit();
    editor.putString("decoderPath", decoderPath());
    editor
        .putInt("width", width())
        .putInt("fps", fps())
        .putInt("bitrate", kbps() / 1000)
        .putString("codec", codec());
    return editor.putString("performance", "custom");
  }

  public String get(String k, String d) {
    return prefs.getString(k, d);
  }

  public int number(String k, int d) {
    return prefs.getInt(k, d);
  }

  public boolean flag(String k, boolean d) {
    return prefs.getBoolean(k, d);
  }

  public void set(String k, String v) {
    ("codec".equals(k) ? customEditor() : prefs.edit()).putString(k, v).apply();
  }

  public void set(String k, int v) {
    (("width".equals(k) || "fps".equals(k) || "bitrate".equals(k)) ? customEditor() : prefs.edit())
        .putInt(k, v)
        .apply();
  }

  public void set(String k, boolean v) {
    SharedPreferences.Editor editor = prefs.edit().putBoolean(k, v);
    if ("directSurface".equals(k)) editor.putString("decoderPath", v ? "direct" : "compatible");
    editor.apply();
  }

  public String engine() {
    return get("engine", "native");
  }

  public String codec() {
    return StreamBudget.codec(budget(), get("codec", "H264"));
  }

  public int width() {
    if (runtimeWidth > 0) return runtimeWidth;
    return StreamBudget.width(budget(), number("width", 1280));
  }

  public int height() {
    if (runtimeHeight > 0) return runtimeHeight;
    return width() == 854 ? 480 : width() == 1920 ? 1080 : 720;
  }

  public int fps() {
    if (runtimeFps > 0) return runtimeFps;
    return StreamBudget.fps(budget(), number("fps", 60));
  }

  public int kbps() {
    return StreamBudget.bitrate(budget(), number("bitrate", 8)) * 1000;
  }

  public Locale locale() {
    String tag = get("language", "system");
    return tag.equals("system") ? Locale.getDefault() : Locale.forLanguageTag(tag);
  }

  public String apiLocale() {
    Locale l = locale();
    String country = l.getCountry();
    if (country.isEmpty()) country = countryFor(l.getLanguage());
    return l.getLanguage() + "_" + country;
  }

  public String gameLocale() {
    String supported =
        ",en_US,en_GB,de_DE,fr_FR,es_ES,es_MX,it_IT,pt_PT,pt_BR,ru_RU,pl_PL,tr_TR,ar_SA,ja_JP,ko_KR,zh_CN,zh_TW,th_TH,vi_VN,id_ID,cs_CZ,el_GR,hu_HU,ro_RO,uk_UA,nl_NL,sv_SE,da_DK,fi_FI,no_NO,";
    String requested = apiLocale();
    if (supported.contains("," + requested + ",")) return requested;
    String fallback = locale().getLanguage() + "_" + countryFor(locale().getLanguage());
    return supported.contains("," + fallback + ",") ? fallback : "en_US";
  }

  private static String countryFor(String l) {
    switch (l) {
      case "pt":
        return "BR";
      case "en":
        return "US";
      case "es":
        return "ES";
      case "fr":
        return "FR";
      case "de":
        return "DE";
      case "ja":
        return "JP";
      case "ko":
        return "KR";
      case "zh":
        return "CN";
      case "it":
        return "IT";
      case "ru":
        return "RU";
      case "tr":
        return "TR";
      case "ar":
        return "SA";
      case "hi":
        return "IN";
      case "id":
        return "ID";
      default:
        return "US";
    }
  }
}
