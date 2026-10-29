package com.bgeforce.tv;

import static org.junit.Assert.*;
import org.junit.Test;

public class EndpointRegressionTest {
  private SessionClient.Seat parse(org.json.JSONArray connections) throws Exception {
    return SessionClient.parse(Json.o("session", Json.o("sessionId", "fixture", "status", 2,
        "connectionInfo", connections)), "https://zone.nvidiagrid.net/");
  }
  @Test public void relativeBrowserEndpointKeepsItsExplicitPort() throws Exception {
    SessionClient.Seat s = parse(Json.a(Json.o("usage", 14, "appLevelProtocol", 4,
        "ip", "browser.nvidiagrid.net", "port", 8443, "resourcePath", "/nvst/custom/")));
    assertEquals("wss://browser.nvidiagrid.net:8443/nvst/custom/", s.signaling);
  }
  @Test public void browserDescriptorWinsOverNativeRtspEvenWhenListedLater() throws Exception {
    SessionClient.Seat s = parse(Json.a(
        Json.o("usage", 14, "appLevelProtocol", 6, "resourcePath", "rtsps://legacy.nvidiagrid.net:322"),
        Json.o("usage", 16, "appLevelProtocol", 4, "ip", "browser.nvidiagrid.net",
            "resourcePath", "/nvst/", "port", 443)));
    assertEquals("wss://browser.nvidiagrid.net/nvst/", s.signaling);
  }
  @Test public void allianceMediaFallbackUsesUdpNotTheRtspPort() throws Exception {
    SessionClient.Seat s = parse(Json.a(
        Json.o("usage", 14, "appLevelProtocol", 6, "resourcePath", "rtsps://native.nvidiagrid.net:48322"),
        Json.o("usage", 14, "appLevelProtocol", 4, "ip", "203.0.113.17", "port", 49100,
            "resourcePath", "/nvst/")));
    assertEquals("203.0.113.17", s.mediaIp);
    assertEquals(49100, s.mediaPort);
  }

  @Test public void malformedPortsAndCredentialsDoNotCreateSignalingEndpoints() {
    for (Object port : new Object[] {0, -1, 65536, 8443.5, true, "bad"})
      assertEquals("", StreamEndpoint.signaling(Json.o("usage", 14, "appLevelProtocol", 4,
          "ip", "game.nvidiagrid.net", "resourcePath", "/nvst/", "port", port)));
    for (String path : new String[] {"rtsps://user:password@game.nvidiagrid.net:322",
        "wss://user:password@game.nvidiagrid.net/nvst/", "ws://game.nvidiagrid.net/nvst/"})
      assertEquals("", StreamEndpoint.signaling(Json.o("usage", 14, "resourcePath", path)));
  }
  @Test public void nativePortNeverBecomesTheWssPort() {
    assertEquals("wss://game.nvidiagrid.net/nvst/", StreamEndpoint.signaling(Json.o(
        "usage", 16, "appLevelProtocol", 6, "ip", "game.nvidiagrid.net", "port", 322)));
  }
  @Test public void primaryAndAlternateMediaOutrankLegacyFallback() throws Exception {
    SessionClient.Seat s = parse(Json.a(
        Json.o("usage", 14, "appLevelProtocol", 4, "ip", "203.0.113.7", "port", 49300),
        Json.o("usage", 17, "ip", "203.0.113.8", "port", 49101),
        Json.o("usage", 2, "ip", "203.0.113.9", "port", 49102)));
    assertEquals("203.0.113.9", s.mediaIp); assertEquals(49102, s.mediaPort);
  }
  @Test public void implicitHttpsPortDoesNotInventAMediaUdpEndpoint() throws Exception {
    SessionClient.Seat s = parse(Json.a(Json.o("usage", 14,
        "resourcePath", "wss://game.nvidiagrid.net/nvst/")));
    assertEquals("", s.mediaIp); assertEquals(0, s.mediaPort);
    assertTrue(s.ready());
  }
}
