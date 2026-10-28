package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.Collections;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class SignalingRouteTest {
  private SessionClient.Seat seat(String resource, String ip) {
    SessionClient.Seat s = new SessionClient.Seat();
    s.id = "existing";
    s.resumed = true;
    s.signaling = "wss://game.nvidiagrid.net/nvst/";
    s.raw =
        Json.o("connectionInfo", Json.a(Json.o("usage", 14, "resourcePath", resource, "ip", ip)));
    return s;
  }

  @Test
  public void resumedWssKeepsTheAuthenticatedNodeWithoutWeakeningTls() throws Exception {
    OkHttpClient original =
        new OkHttpClient.Builder()
            .dns(n -> Collections.singletonList(InetAddress.getByName("203.0.113.9")))
            .build();
    OkHttpClient route =
        SessionRoute.signaling(original, seat("wss://game.nvidiagrid.net/nvst/", "203.0.113.8"));
    assertNotSame(original, route);
    assertNotSame(original.connectionPool(), route.connectionPool());
    assertSame(original.sslSocketFactory(), route.sslSocketFactory());
    assertSame(original.hostnameVerifier(), route.hostnameVerifier());
    assertEquals("203.0.113.8", route.dns().lookup("game.nvidiagrid.net").get(0).getHostAddress());
    assertEquals("203.0.113.9", route.dns().lookup("other.nvidiagrid.net").get(0).getHostAddress());
  }

  @Test
  public void unrelatedConnectionCannotRedirectTheSignalingHostname() {
    OkHttpClient original = new OkHttpClient();
    assertSame(
        original,
        SessionRoute.signaling(original, seat("wss://other.nvidiagrid.net/nvst/", "203.0.113.8")));
  }

  @Test
  public void privateAndInvalidNodeAddressesDoNotOverrideDns() {
    OkHttpClient original = new OkHttpClient();
    for (String ip :
        new String[] {
          "127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.0.1", "224.0.0.1", "999.1.1.1"
        })
      assertSame(
          original, SessionRoute.signaling(original, seat("wss://game.nvidiagrid.net/nvst/", ip)));
  }

  @Test
  public void freshSessionAlsoUsesItsAuthenticatedAssignedNode() throws Exception {
    OkHttpClient original = new OkHttpClient();
    SessionClient.Seat s = seat("wss://game.nvidiagrid.net/nvst/", "203.0.113.8");
    s.resumed = false;
    OkHttpClient route = SessionRoute.signaling(original, s);
    assertNotSame(original, route);
    assertSame(original.sslSocketFactory(), route.sslSocketFactory());
    assertSame(original.hostnameVerifier(), route.hostnameVerifier());
    assertEquals("203.0.113.8", route.dns().lookup("game.nvidiagrid.net").get(0).getHostAddress());
  }

  @Test public void freshRtspResourcePinsOnlyItsMatchingSignalingHostname() throws Exception {
    OkHttpClient original = new OkHttpClient();
    SessionClient.Seat s = seat("rtsps://game.nvidiagrid.net:322", "203.0.113.8");
    s.resumed = false;
    assertEquals("203.0.113.8", SessionRoute.signaling(original, s).dns()
        .lookup("game.nvidiagrid.net").get(0).getHostAddress());
  }
}
