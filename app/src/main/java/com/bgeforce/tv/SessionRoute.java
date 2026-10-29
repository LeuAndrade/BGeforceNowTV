package com.bgeforce.tv;

import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/**
 * Reach an authenticated game-server IP without reusing a zone-LB connection for RESUME. The HTTPS
 * URL, SNI, certificate and hostname verification keep the trusted service identity.
 */
final class SessionRoute {
  /** Apply the authenticated game-node route to WSS as well, retaining its DNS TLS identity. */
  static OkHttpClient signaling(OkHttpClient original, SessionClient.Seat seat) {
    return route(original, seat, seat.signaling, true);
  }

  private static OkHttpClient route(
      OkHttpClient original, SessionClient.Seat seat, String target, boolean websocket) {
    HttpUrl url = HttpUrl.parse(target.replaceFirst("^wss://", "https://"));
    if (url == null) return original;
    try {
      Api.validateServiceUrl(url.toString());
    } catch (java.io.IOException ignored) {
      return original;
    }
    JSONArray list = websocket && seat.signalingConnection != null
        ? Json.a(seat.signalingConnection) : Json.arr(seat.raw, "connectionInfo");
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      if (c == null || (c.optInt("usage") != 14 && c.optInt("usage") != 16)) continue;
      String resource = Json.s(c, "resourcePath");
      HttpUrl supplied = HttpUrl.parse(resource.replaceFirst("^(wss|rtsps?)://", "https://"));
      // Only the IP paired with this exact signaling hostname may override its DNS.
      if (supplied == null || !supplied.host().equals(url.host())) continue;
      if (websocket) {
        HttpUrl candidate = HttpUrl.parse(
            StreamEndpoint.signaling(c).replaceFirst("^wss://", "https://"));
        if (candidate == null || candidate.port() != url.port()
            || !candidate.encodedPath().equals(url.encodedPath())) continue;
      }
      String ip = ServiceAddress.first(c.opt("ip"));
      InetAddress address = publicIpv4(ip);
      if (address == null) continue;
      Dns dns = original.dns();
      return original
          .newBuilder()
          .dns(
              host ->
                  host.equals(url.host()) ? Collections.singletonList(address) : dns.lookup(host))
          .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
          .build();
    }
    return original;
  }

  static OkHttpClient read(OkHttpClient original, SessionClient.Seat seat, String base) {
    // GET uses normal DNS for regional brokers; only a matching game-node host can be pinned.
    return route(original, seat, base, false);
  }

  private static InetAddress publicIpv4(String ip) {
    if (!ip.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return null;
    try {
      InetAddress address = InetAddress.getByName(ip);
      return address.isAnyLocalAddress()
              || address.isLoopbackAddress()
              || address.isSiteLocalAddress()
              || address.isLinkLocalAddress()
              || address.isMulticastAddress()
          ? null
          : address;
    } catch (UnknownHostException | IllegalArgumentException ignored) {
      return null;
    }
  }

  static OkHttpClient claim(OkHttpClient original, SessionClient.Seat seat, String base) {
    // The game IP must not replace a different controller/broker DNS identity on RESUME.
    return route(original, seat, base, false);
  }
}
