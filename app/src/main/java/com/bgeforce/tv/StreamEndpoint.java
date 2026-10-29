package com.bgeforce.tv;

import okhttp3.HttpUrl;
import org.json.*;

/** Connection descriptors keep their role, scheme and browser port through session handoff. */
final class StreamEndpoint {
  private StreamEndpoint() {}

  static boolean rtsp(JSONObject c) {
    String path = Json.s(c, "resourcePath");
    return path.startsWith("rtsp://") || path.startsWith("rtsps://");
  }

  static int priority(JSONObject c) {
    String path = Json.s(c, "resourcePath");
    if (!rtsp(c) && c.optInt("appLevelProtocol", -1) == 5) return 0;
    if (path.startsWith("wss://") || path.startsWith("https://")) return 1;
    if (!rtsp(c) && c.optInt("appLevelProtocol", -1) == 4) return 2;
    if (!rtsp(c) && c.optInt("usage") == 14
        && c.optInt("appLevelProtocol") != 1 && c.optInt("appLevelProtocol") != 6) return 3;
    return c.optInt("usage") == 14 ? 4 : 5;
  }

  static HttpUrl resource(JSONObject c) {
    String value = Json.s(c, "resourcePath");
    if (!(value.startsWith("wss://") || value.startsWith("https://") || rtsp(c))) return null;
    HttpUrl u = HttpUrl.parse(value.replaceFirst("^(wss|rtsps?)://", "https://"));
    return u != null && u.username().isEmpty() && u.password().isEmpty()
        && u.fragment() == null ? u : null;
  }

  static String host(JSONObject c) {
    String ip = ServiceAddress.first(c.opt("ip"));
    HttpUrl resource = resource(c);
    return !ip.isEmpty() ? ip : resource == null ? "" : resource.host();
  }

  static int port(JSONObject c) {
    Object value = c.opt("port");
    if (value == null || value == JSONObject.NULL) {
      // HTTPS's implicit 443 is a signaling default, not evidence of a media UDP port.
      if (resource(c) == null) return 0;
      try {
        int p = new java.net.URI(Json.s(c, "resourcePath")).getPort();
        return p >= 1 && p <= 65535 ? p : 0;
      } catch (java.net.URISyntaxException invalid) { return 0; }
    }
    if (value instanceof Boolean) return 0;
    try {
      double p = Double.parseDouble(value.toString());
      return p >= 1 && p <= 65535 && p == Math.rint(p) ? (int) p : 0;
    } catch (NumberFormatException invalid) { return 0; }
  }

  static String signaling(JSONObject c) {
    String path = Json.s(c, "resourcePath");
    HttpUrl u = resource(c);
    if (u != null) {
      if (rtsp(c)) u = u.newBuilder().port(443).encodedPath("/nvst/").query(null).build();
    } else {
      if (path.contains("://") || (!path.isEmpty() && !path.startsWith("/"))) return "";
      String host = ServiceAddress.first(c.opt("ip"));
      if (host.isEmpty()) return "";
      try {
        HttpUrl.Builder b = HttpUrl.get("https://endpoint.invalid/").newBuilder().host(host)
            .encodedPath(path.isEmpty() ? "/nvst/" : path);
        // Legacy native descriptors' port is RTSP/media, not WSS. A browser descriptor's
        // explicit port belongs to WSS and must not be silently replaced by 443.
        if ((c.optInt("appLevelProtocol", -1) == 4
            || c.optInt("appLevelProtocol", -1) == 5) && c.has("port")) {
          int port = port(c); if (port == 0) return ""; b.port(port);
        }
        u = b.build();
      } catch (IllegalArgumentException invalid) { return ""; }
    }
    return u == null ? "" : u.toString().replaceFirst("^https://", "wss://");
  }

  static JSONObject media(JSONArray connections) {
    for (int usage : new int[] {2, 17})
      for (int i = 0; i < connections.length(); i++) {
        JSONObject c = connections.optJSONObject(i);
        if (c != null && c.optInt("usage") == usage && !host(c).isEmpty() && port(c) > 0)
          return c;
      }
    JSONObject best = null;
    for (int i = 0; i < connections.length(); i++) {
      JSONObject c = connections.optJSONObject(i);
      if (c == null || c.optInt("usage") != 14 || rtsp(c)
          || c.optInt("appLevelProtocol") == 1 || c.optInt("appLevelProtocol") == 6
          || c.optInt("appLevelProtocol") == 5
          || host(c).isEmpty() || port(c) == 0) continue;
      if (best == null || port(c) > port(best)) best = c;
    }
    return best;
  }
}
