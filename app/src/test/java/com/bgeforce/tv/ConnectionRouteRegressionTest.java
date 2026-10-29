package com.bgeforce.tv;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.util.*;
import okhttp3.*;
import org.junit.Test;

public class ConnectionRouteRegressionTest {
  private SessionClient.Seat seat() throws Exception {
    return SessionClient.parse(Json.o("session", Json.o("sessionId", "fixture", "status", 3,
        "sessionControlInfo", Json.o("ip", "controller.nvidiagrid.net", "port", 443),
        "connectionInfo", Json.a(
            Json.o("usage", 14, "appLevelProtocol", 6, "ip", "203.0.113.7",
                "resourcePath", "rtsps://game.nvidiagrid.net:322"),
            Json.o("usage", 14, "appLevelProtocol", 4, "ip", "203.0.113.8",
                "resourcePath", "wss://game.nvidiagrid.net/nvst/")))),
        "https://zone.nvidiagrid.net/");
  }
  private OkHttpClient client() {
    return new OkHttpClient.Builder().dns(host ->
        Collections.singletonList(InetAddress.getByName("203.0.113.20"))).build();
  }
  @Test public void resumeDoesNotPinTheControllerHostnameToAnUnrelatedGameIp() throws Exception {
    OkHttpClient normal = client();
    SessionClient.Seat s = seat();
    assertSame(normal, SessionRoute.claim(normal, s, s.control));
    assertSame(normal, SessionRoute.claim(normal, s, s.base));
  }
  @Test public void signalingUsesTheSelectedBrowserDescriptorNotAnEarlierNativeIp()
      throws Exception {
    OkHttpClient normal = client();
    OkHttpClient routed = SessionRoute.signaling(normal, seat());
    assertEquals("203.0.113.8", routed.dns().lookup("game.nvidiagrid.net").get(0).getHostAddress());
    assertSame(normal.sslSocketFactory(), routed.sslSocketFactory());
    assertSame(normal.hostnameVerifier(), routed.hostnameVerifier());
  }
  @Test public void resumeTargetsTheAuthenticatedControllerBeforeTheSignalingGameHost()
      throws Exception {
    assertEquals("https://controller.nvidiagrid.net/",
        SessionClient.resumeTargets(seat()).iterator().next());
  }
}
