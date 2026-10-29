package com.bgeforce.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Server addresses may be a string or a list; never turn a JSON array into a URL hostname. */
final class ServiceAddress {
  static List<String> hosts(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof String) add(out, (String) value);
    else if (value instanceof JSONArray) {
      JSONArray list = (JSONArray) value;
      for (int i = 0; i < Math.min(list.length(), 16); i++) {
        Object item = list.opt(i);
        if (item instanceof String) add(out, (String) item);
      }
    }
    return out;
  }

  private static void add(List<String> out, String raw) {
    String host = raw.trim();
    if (host.isEmpty() || host.contains("/") || host.contains("@")
        || host.contains("?") || host.contains("#") || host.contains("%")) return;
    try {
      String normalized = new HttpUrl.Builder().scheme("https").host(host).build().host();
      if (!out.contains(normalized)) out.add(normalized);
    } catch (IllegalArgumentException ignored) { }
  }

  static String first(Object value) {
    List<String> hosts = hosts(value);
    return hosts.isEmpty() ? "" : hosts.get(0);
  }

  static String control(JSONObject session) {
    JSONObject info = Json.obj(session, "sessionControlInfo");
    int port = 443;
    if (info.has("port") && !info.isNull("port")) {
      try {
        double n = Double.parseDouble(info.opt("port").toString());
        if (n != Math.rint(n) || n < 0 || n > 65535) return "";
        port = n == 0 ? 443 : (int) n;
      } catch (NumberFormatException invalid) { return ""; }
    }
    for (String host : hosts(info.opt("ip"))) {
      String url = new HttpUrl.Builder().scheme("https").host(host).port(port).build().toString();
      try { Api.validateServiceUrl(url); return url; }
      catch (IOException ignored) { }
    }
    return "";
  }
}
