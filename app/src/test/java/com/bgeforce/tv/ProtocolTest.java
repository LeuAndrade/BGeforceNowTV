package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.nio.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

/** Wire-level fixtures and failure cases, independent of a paid GFN session. */
public final class ProtocolTest {
  private static final String OFFER =
      "v=0\r\n"
          + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
          + "a=rtpmap:111 opus/48000/2\r\n"
          + "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99\r\n"
          + "a=rtpmap:96 H264/90000\r\n"
          + "a=fmtp:96 packetization-mode=1\r\n"
          + "a=rtpmap:97 rtx/90000\r\n"
          + "a=fmtp:97 apt=96\r\n"
          + "a=rtpmap:98 AV1/90000\r\n"
          + "a=rtpmap:99 rtx/90000\r\n"
          + "a=fmtp:99 apt=98\r\n"
          + "a=rtcp-fb:* transport-cc\r\n"
          + "a=ri.version:3\r\n";

  @Test
  public void codecSelectionPreservesAudioAndMatchingRepair() {
    String s = Sdp.prefer(OFFER, "H264");
    assertTrue(s.contains("m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n"));
    assertTrue(s.contains("opus/48000/2"));
    assertTrue(s.contains("a=fmtp:97 apt=96"));
    assertTrue(s.contains("a=rtcp-fb:*"));
    assertFalse(s.contains("a=rtpmap:98"));
    assertFalse(s.contains("a=fmtp:99"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unavailableCodecFailsBeforePlaying() {
    Sdp.prefer(OFFER, "H265");
  }

  @Test
  public void protocolReadsServerVersion() {
    assertEquals(3, Sdp.protocol(OFFER));
    assertEquals(2, Sdp.protocol("a=ri.version:2\n"));
    assertEquals(3, Sdp.protocol(""));
  }

  @Test
  public void privateCandidatesUseAuthenticatedMediaEndpoint() {
    String s =
        "c=IN IP4 10.0.0.8\r\n"
            + "a=candidate:1 1 UDP 123 10.0.0.8 9999 typ host\r\n"
            + "a=candidate:2 1 UDP 123 8.8.8.8 1111 typ srflx\r\n";
    String out = Sdp.serverEndpoint(s, "80.80.80.80", 49005);
    assertTrue(out.contains("c=IN IP4 80.80.80.80"));
    assertTrue(out.contains("80.80.80.80 49005 typ host"));
    assertTrue(out.contains("8.8.8.8 1111 typ srflx"));
  }

  @Test
  public void invalidEndpointCannotInjectSdp() {
    assertEquals(OFFER, Sdp.serverEndpoint(OFFER, "evil\r\na=bad", 1));
  }

  @Test
  public void nvstCarriesSelectedProfileAndRealIceCredentials() {
    String s =
        Sdp.nvst(
            OFFER,
            "a=ice-ufrag:abc\r\na=ice-pwd:xyz\r\na=fingerprint:sha-256 00:11\r\n",
            1280,
            720,
            30,
            8000,
            false);
    assertTrue(s.contains("a=video.clientViewportWd:1280"));
    assertTrue(s.contains("a=video.maxFPS:30"));
    assertTrue(s.contains("maximumBitrateKbps:8000"));
    assertTrue(s.contains("a=general.icePassword:xyz"));
    assertTrue(s.contains("a=general.dtlsFingerprint:00:11"));
    assertTrue(s.contains("a=vqos.drc.enable:0"));
    assertTrue(s.contains("a=vqos.dfc.enable:0"));
  }

  @Test
  public void adaptiveResolutionIsExplicit() {
    String s = Sdp.nvst("", "", 854, 480, 30, 3000, true);
    assertTrue(s.contains("a=vqos.drc.enable:1"));
    assertTrue(s.contains("a=vqos.bw.maximumBitrateKbps:3000"));
  }

  @Test
  public void heartbeatMatchesProtocol() {
    assertArrayEquals(new byte[] {2, 0, 0, 0}, InputEncoder.heartbeat());
  }

  @Test
  public void gamepadGoldenPacketV2() {
    InputEncoder e = new InputEncoder();
    e.version = 2;
    byte[] data = e.gamepad(0x1001, 255, 128, 32767, -32768, 0, 1, 123L);
    assertEquals(38, data.length);
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(12, b.getInt(0));
    assertEquals(26, b.getShort(4));
    assertEquals(1, b.getShort(8));
    assertEquals(0x1001, b.getShort(12));
    assertEquals((short) 0x80ff, b.getShort(14));
    assertEquals(32767, b.getShort(16));
    assertEquals(-32768, b.getShort(18));
    assertEquals(85, b.getShort(26));
    assertEquals(123L, b.getLong(30));
  }

  @Test
  public void v3FramingHasBigEndianTimeAndLength() {
    InputEncoder e = new InputEncoder();
    byte[] data = e.gamepad(0, 0, 0, 0, 0, 0, 0, 0x0102030405060708L);
    assertEquals(50, data.length);
    assertEquals(0x23, data[0]);
    assertEquals(0x21, data[9]);
    assertEquals(38, ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getShort(10));
    assertEquals(0x0102030405060708L, ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getLong(1));
    assertEquals(12, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(12));
  }

  @Test
  public void keyboardSingleFrameHasCorrectOrders() {
    InputEncoder e = new InputEncoder();
    byte[] d = e.key(true, 65, 30, 2, 123);
    assertEquals(28, d.length);
    assertEquals(0x22, d[9]);
    assertEquals(3, ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt(10));
    assertEquals(65, ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN).getShort(14));
  }

  @Test
  public void queueUsesServerValueNotAnEstimate() {
    assertEquals(0, SessionClient.queue(Json.o("queuePosition", 0)));
    assertEquals(43, SessionClient.queue(Json.o("seatSetupInfo", Json.o("queuePosition", 43))));
    assertEquals(-1, SessionClient.queue(new JSONObject()));
  }

  @Test
  public void relativeSignalingIgnoresMediaPort() throws Exception {
    SessionClient.Seat s =
        SessionClient.parse(
            Json.o(
                "session",
                Json.o(
                    "sessionId",
                    "test",
                    "status",
                    2,
                    "connectionInfo",
                    Json.a(
                        Json.o(
                            "usage",
                            14,
                            "ip",
                            "example.nvidiagrid.net",
                            "port",
                            49003,
                            "resourcePath",
                            "/nvst/")))),
            Api.DEFAULT_BASE);
    assertEquals("wss://example.nvidiagrid.net/nvst/", s.signaling);
    assertTrue(s.ready());
  }

  @Test
  public void absoluteSignalingRetainsExplicitPort() throws Exception {
    SessionClient.Seat s =
        SessionClient.parse(
            Json.o(
                "session",
                Json.o(
                    "sessionId",
                    "test",
                    "status",
                    3,
                    "connectionInfo",
                    Json.a(
                        Json.o(
                            "usage",
                            14,
                            "resourcePath",
                            "wss://example.nvidiagrid.net:444/nvst/")))),
            Api.DEFAULT_BASE);
    assertTrue(s.signaling.contains(":444/"));
  }

  @Test
  public void queueDoesNotRequireSignalingYet() throws Exception {
    SessionClient.Seat s =
        SessionClient.parse(
            Json.o("session", Json.o("sessionId", "test", "status", 1, "queuePosition", 123)),
            Api.DEFAULT_BASE);
    assertFalse(s.ready());
    assertEquals(123, s.queue);
  }

  @Test(expected = java.io.IOException.class)
  public void endedSessionIsTerminal() throws Exception {
    SessionClient.parse(
        Json.o("session", Json.o("sessionId", "test", "status", 4)), Api.DEFAULT_BASE);
  }

  @Test
  public void mediaAndSignalingRemainSeparate() throws Exception {
    SessionClient.Seat s =
        SessionClient.parse(
            Json.o(
                "session",
                Json.o(
                    "sessionId",
                    "test",
                    "status",
                    2,
                    "connectionInfo",
                    Json.a(
                        Json.o("usage", 2, "ip", "80.80.80.80", "port", 49005),
                        Json.o(
                            "usage",
                            14,
                            "ip",
                            "example.nvidiagrid.net",
                            "resourcePath",
                            "/nvst/")))),
            Api.DEFAULT_BASE);
    assertEquals(49005, s.mediaPort);
    assertEquals("80.80.80.80", s.mediaIp);
    assertFalse(s.signaling.contains("49005"));
  }

  @Test
  public void ownershipIsPerStoreAndUnknownIsNotOwned() {
    Game g =
        new Game(
            Json.o(
                "id",
                "g1",
                "title",
                "Fixture",
                "variants",
                Json.a(
                    Json.o(
                        "id",
                        "1",
                        "appStore",
                        "STEAM",
                        "gfn",
                        Json.o("library", Json.o("status", "PLATFORM_SYNC"))),
                    Json.o(
                        "id",
                        "2",
                        "appStore",
                        "EPIC",
                        "gfn",
                        Json.o("library", Json.o("status", "NOT_OWNED"))),
                    Json.o(
                        "id", "3", "gfn", Json.o("library", Json.o("status", "FUTURE_STATUS"))))));
    assertTrue(g.owned());
    assertTrue(g.variants.get(0).owned);
    assertFalse(g.variants.get(1).owned);
    assertFalse(g.variants.get(2).owned);
  }

  @Test
  public void freeToPlayComesFromPaymentMetadata() {
    Game g =
        new Game(
            Json.o(
                "id",
                "x",
                "title",
                "Fixture",
                "variants",
                Json.a(
                    Json.o(
                        "id", "1", "paymentModels", Json.a(Json.o("__typename", "FreeToPlay"))))));
    assertTrue(g.free());
    assertFalse(g.owned());
  }

  @Test
  public void remoteCategoriesKeepTheirOrderAndNames() {
    JSONObject app = Json.o("id", "a", "title", "A");
    JSONObject root =
        Json.o(
            "main",
            Json.o(
                "data",
                Json.o(
                    "panels",
                    Json.a(
                        Json.o(
                            "sections",
                            Json.a(
                                Json.o(
                                    "id",
                                    "thursday",
                                    "title",
                                    "Quinta GFN",
                                    "items",
                                    Json.a(Json.o("app", app))),
                                Json.o(
                                    "id",
                                    "capcom",
                                    "title",
                                    "Capcom",
                                    "items",
                                    Json.a(Json.o("app", app)))))))),
            "savedAt",
            100L);
    Catalog.Home home = Catalog.parseHome(root);
    assertEquals("Quinta GFN", home.shelves.get(0).title);
    assertEquals("Capcom", home.shelves.get(1).title);
    assertEquals("A", home.banners.get(0).title);
  }

  @Test
  public void allUiLanguagesHaveCompletePrimaryDictionary() {
    for (String tag :
        new String[] {
          "pt-BR", "en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "zh-CN", "ja-JP", "ko-KR", "ru-RU",
          "ar-SA", "hi-IN", "tr-TR", "id-ID"
        }) {
      L l = new L(Locale.forLanguageTag(tag));
      assertNotEquals("loginHint", l.t("loginHint"));
      assertNotEquals("microphoneHint", l.t("microphoneHint"));
    }
    assertEquals("Home", new L(Locale.forLanguageTag("xx")).t("home"));
  }

  @Test
  public void trustedServicesUseHttps() throws Exception {
    Api.validateServiceUrl(Api.DEFAULT_BASE);
    Api.validateServiceUrl("https://prod.aby.geforcenow.nvidiagrid.net/");
  }

  @Test(expected = java.io.IOException.class)
  public void httpServiceIsRejected() throws Exception {
    Api.validateServiceUrl("http://prod.cloudmatchbeta.nvidiagrid.net/");
  }

  @Test(expected = java.io.IOException.class)
  public void hostSuffixConfusionIsRejected() throws Exception {
    Api.validateServiceUrl("https://nvidiagrid.net.attacker.example/");
  }

  @Test(expected = java.io.IOException.class)
  public void urlCredentialsAreRejected() throws Exception {
    Api.validateServiceUrl("https://user:pass@prod.cloudmatchbeta.nvidiagrid.net/");
  }
}
