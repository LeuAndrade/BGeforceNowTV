package com.bgeforce.tv;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import okhttp3.*;
import okio.Buffer;
import org.json.*;
import org.junit.Test;

/**
 * Production SessionClient + Api + OkHttp, with controlled server responses, no GFN credentials.
 */
public class SessionFlowTest {
  private static final String PARTNER = "https://prod.aby.geforcenow.nvidiagrid.net/";
  private static final String REGION = "https://np-aby-01.geforcenow.nvidiagrid.net/";

  static Settings settings(Map<String, Object> values) {
    SharedPreferences prefs =
        (SharedPreferences)
            Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class[] {SharedPreferences.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getString")
                      || method.getName().equals("getInt")
                      || method.getName().equals("getBoolean"))
                    return values.getOrDefault(args[0], args[1]);
                  throw new UnsupportedOperationException(method.getName());
                });
    return new Settings(prefs);
  }

  static Game game() {
    return new Game(
        Json.o(
            "id",
            "fixture",
            "title",
            "Fixture",
            "variants",
            Json.a(Json.o("id", "12345", "gfn", Json.o("library", Json.o("status", "MANUAL"))))));
  }

  static Response response(Request r, int code, JSONObject payload) {
    return new Response.Builder()
        .request(r)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fixture")
        .header("x-request-id", "fixture-request")
        .body(ResponseBody.create(payload.toString(), MediaType.get("application/json")))
        .build();
  }

  static Api api(String base, Map<String, Object> prefs, Interceptor interceptor) throws Exception {
    ApiFlowTest.MemoryAccount account = new ApiFlowTest.MemoryAccount();
    account.saved.put("base", base);
    account.saved.getJSONObject("user").put("age", 37);
    Api api =
        new Api(
            account,
            settings(prefs),
            new OkHttpClient.Builder().addInterceptor(interceptor).build());
    assertTrue(api.restore());
    return api;
  }

  static JSONObject queue(int status) {
    return Json.o(
        "requestStatus",
        Json.o("statusCode", 1),
        "session",
        Json.o("sessionId", "fixture-session", "status", status, "queuePosition", 7));
  }

  @Test
  public void partnerDiscoveryCreateQueuePollReadyAndStop() throws Exception {
    List<Request> requests = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    Api api =
        api(
            PARTNER,
            Collections.singletonMap("language", "pt-BR"),
            chain -> {
              Request r = chain.request();
              requests.add(r);
              assertEquals("GFNJWT identity-old", r.header("Authorization"));
              if (r.url().encodedPath().equals("/v2/serverInfo")) {
                assertEquals(HttpUrl.get(PARTNER).host(), r.url().host());
                return response(
                    r,
                    200,
                    Json.o(
                        "metaData",
                        Json.a(
                            Json.o("key", "gfn-regions", "value", "ignored"),
                            Json.o("key", "SA", "value", REGION))));
              }
              assertTrue(r.url().host().equals(HttpUrl.get(REGION).host())
                  || r.url().host().equals("correct.nvidiagrid.net"));
              if (r.url().host().equals("correct.nvidiagrid.net"))
                assertTrue(r.method().equals("GET") || r.method().equals("DELETE"));
              assertEquals("NATIVE", r.header("nv-client-type"));
              assertEquals("NVIDIA-CLASSIC", r.header("nv-client-streamer"));
              assertEquals("fixture-device", r.header("x-device-id"));
              ids.add(r.header("nv-client-id"));
              if (r.method().equals("POST")) {
                assertEquals("pt_BR", r.url().queryParameter("languageCode"));
                assertEquals("en-US", r.url().queryParameter("keyboardLayout"));
                Buffer buffer = new Buffer();
                r.body().writeTo(buffer);
                JSONObject body = Json.obj(Json.parse(buffer.readUtf8()), "sessionRequestData");
                assertEquals("12345", body.optString("appId"));
                assertEquals("windows", body.optString("clientPlatformName"));
                assertEquals(1, body.optInt("streamerVersion"));
                assertEquals(1, body.optInt("enhancedStreamMode"));
                assertTrue(body.optBoolean("accountLinked"));
                assertEquals(
                    100,
                    body.optJSONArray("clientRequestMonitorSettings")
                        .optJSONObject(0)
                        .optInt("dpi"));
                assertEquals(2, body.optJSONArray("availableSupportedControllers").optInt(0));
                assertEquals("https://play.geforcenow.com", r.header("Origin"));
                return response(r, 200, queue(1));
              }
              assertNull(r.header("Origin"));
              assertNull(r.header("Referer"));
              if (r.method().equals("DELETE"))
                return response(r, 200, Json.o("requestStatus", Json.o("statusCode", 1)));
              JSONObject ready = queue(2);
              try {
                ready
                    .getJSONObject("session")
                    .put(
                        "connectionInfo",
                        Json.a(
                            Json.o(
                                "usage",
                                16,
                                "resourcePath",
                                "rtsps://wrong.nvidiagrid.net:49003/stream"),
                            Json.o(
                                "usage",
                                14,
                                "resourcePath",
                                "wss://correct.nvidiagrid.net/nvst/")));
              } catch (JSONException e) {
                throw new IOException(e);
              }
              return response(r, 200, ready);
            });
    SessionClient client = new SessionClient(api);
    Game game = game();
    SessionClient.Seat seat = client.create(game, game.selected());
    assertFalse(seat.ready());
    assertEquals(7, seat.queue);
    seat = client.poll(seat);
    assertTrue(seat.ready());
    assertEquals("wss://correct.nvidiagrid.net/nvst/", seat.signaling);
    assertEquals(3, seat.ice.length());
    client.stop(seat);
    assertEquals(5, requests.size());
    assertEquals(1, new HashSet<>(ids).size());
  }

  @Test
  public void nvidiaWebViewCreateUsesConsistentBrowserProfile() throws Exception {
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.singletonMap("engine", "webview"),
            chain -> {
              Request r = chain.request();
              assertEquals("POST", r.method()); // no provider-root discovery on NVIDIA
              assertEquals("BROWSER", r.header("nv-client-type"));
              assertEquals("WEBRTC", r.header("nv-client-streamer"));
              Buffer b = new Buffer();
              r.body().writeTo(b);
              JSONObject request = Json.obj(Json.parse(b.readUtf8()), "sessionRequestData");
              assertEquals("browser", Json.s(request, "clientPlatformName"));
              assertFalse(request.optBoolean("enablePersistingInGameSettings"));
              assertEquals(
                  0,
                  Json.arr(request, "clientRequestMonitorSettings").optJSONObject(0).optInt("dpi"));
              return response(r, 200, queue(1));
            });
    Game game = game();
    new SessionClient(api).create(game, game.selected());
  }

  @Test
  public void server500KeepsInternalCodeEndpointAndCorrelationWithoutReposting() throws Exception {
    int[] calls = {0};
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              calls[0]++;
              return response(
                  chain.request(),
                  500,
                  Json.o(
                      "requestStatus",
                      Json.o(
                          "statusCode",
                          4,
                          "statusDescription",
                          "INTERNAL_ERROR_STATUS",
                          "unifiedErrorCode",
                          "8A8C0000")));
            });
    try {
      Game g = game();
      new SessionClient(api).create(g, g.selected());
      fail();
    } catch (Api.Failure error) {
      assertEquals(500, error.code);
      assertTrue(error.getMessage().contains("POST prod.cloudmatchbeta.nvidiagrid.net/v2/session"));
      assertTrue(error.getMessage().contains("8A8C0000"));
      assertTrue(error.getMessage().contains("statusCode=4"));
      assertTrue(error.getMessage().contains("requestId=fixture-request"));
    }
    assertEquals(1, calls[0]);
    assertTrue(api.signedIn());
  }

  @Test
  public void successfulHttpWithGfnFailureIsNotASeat() throws Exception {
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain ->
                response(
                    chain.request(),
                    200,
                    Json.o(
                        "requestStatus", Json.o("statusCode", 4, "unifiedErrorCode", "8A8C0000"))));
    try {
      Game g = game();
      new SessionClient(api).create(g, g.selected());
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("8A8C0000"));
    }
  }

  @Test
  public void provisioningStatusSixMustNotEndTheSession() throws Exception {
    SessionClient.Seat seat = SessionClient.parse(queue(6), Api.DEFAULT_BASE);
    assertFalse(seat.ready());
    assertEquals(6, seat.status);
  }

  @Test
  public void multipleRegionsDoNotSilentlySelectAnArbitraryServer() throws Exception {
    assertEquals(
        PARTNER,
        SessionClient.regionalBase(
            PARTNER,
            Json.a(
                Json.o("key", "one", "value", REGION),
                Json.o("key", "two", "value", "https://np-aby-02.geforcenow.nvidiagrid.net/"))));
  }

  @Test
  public void metadataRejectsUntrustedAndNonHttpsRegions() throws Exception {
    assertEquals(
        PARTNER,
        SessionClient.regionalBase(
            PARTNER,
            Json.a(
                Json.o("key", "one", "value", "http://np-aby-01.geforcenow.nvidiagrid.net/"),
                Json.o("key", "two", "value", "https://attacker.example/"),
                Json.o("key", "gfn-hidden", "value", REGION))));
  }

  @Test
  public void selectedRegionIsNotOverriddenByDiscovery() throws Exception {
    Api api =
        api(
            PARTNER,
            Collections.singletonMap("serverUrl", REGION),
            chain -> {
              assertEquals("POST", chain.request().method());
              assertEquals(HttpUrl.get(REGION).host(), chain.request().url().host());
              return response(chain.request(), 200, queue(1));
            });
    Game g = game();
    new SessionClient(api).create(g, g.selected());
  }

  @Test
  public void identityClassifierUsesDomainBoundaries() {
    assertTrue(SessionClient.isNvidia(Api.DEFAULT_BASE));
    assertFalse(SessionClient.isNvidia("https://notcloudmatchbeta.nvidiagrid.net/"));
    assertFalse(SessionClient.isNvidia("https://cloudmatchbeta.nvidiagrid.net.attacker.example/"));
  }

  @Test
  public void nonemptyIceListIsPreserved() throws Exception {
    JSONObject payload = queue(2);
    payload
        .getJSONObject("session")
        .put(
            "iceServerConfiguration",
            Json.o(
                "iceServers",
                Json.a(
                    Json.o(
                        "urls",
                        Json.a("turn:fixture.example:443"),
                        "username",
                        "fixture",
                        "credential",
                        "fixture"))));
    SessionClient.Seat seat = SessionClient.parse(payload, Api.DEFAULT_BASE);
    assertEquals(1, seat.ice.length());
    assertEquals("fixture", Json.s(seat.ice.optJSONObject(0), "credential"));
  }

  @Test
  public void diagnosticsHideCredentialsAndKeepStatusCodes() {
    String value =
        Diagnostics.safe(
            "statusCode=4 unifiedErrorCode=8A8C0000 Bearer secret-value "
                + "id_token=secret-id sessionId=secret-session user@example.com");
    assertFalse(value.contains("secret-"));
    assertFalse(value.contains("user@example.com"));
    assertTrue(value.contains("statusCode=4"));
    assertTrue(value.contains("8A8C0000"));
  }

  @Test
  public void malformedVariantDoesNotMakeNetworkRequests() throws Exception {
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              fail("unexpected HTTP");
              return null;
            });
    try {
      new SessionClient(api).create(game(), new Game.Variant(Json.o("id", "not-numeric")));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("numeric"));
    }
  }

  @Test
  public void explicitRetryRecoversSameDeviceAndGameWithoutDuplicatingPost() throws Exception {
    int[] posts = {0}, gets = {0};
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.method().equals("POST")) {
                posts[0]++;
                return response(r, 500, Json.o("message", "Fixture"));
              }
              gets[0]++;
              JSONObject candidate =
                  Json.o(
                      "sessionId",
                      "recover-fixture",
                      "status",
                      1,
                      "sessionRequestData",
                      Json.o("appId", "12345", "deviceHashId", "fixture-device"));
              return response(
                  r,
                  200,
                  Json.o("requestStatus", Json.o("statusCode", 1), "sessions", Json.a(candidate)));
            });
    SessionClient client = new SessionClient(api);
    Game g = game();
    try {
      client.create(g, g.selected());
      fail();
    } catch (Api.Failure expected) {
    }
    assertEquals("recover-fixture", client.recoverOrCreate(g, g.selected()).id);
    assertEquals(1, posts[0]);
    assertEquals(1, gets[0]);
  }

  @Test
  public void explicitRetryNeverTakesAnotherDevicesSession() throws Exception {
    int[] posts = {0};
    Api api =
        api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.method().equals("POST")) {
                posts[0]++;
                return response(r, 200, queue(1));
              }
              return response(
                  r,
                  200,
                  Json.o(
                      "sessions",
                      Json.a(
                          Json.o(
                              "sessionId",
                              "other",
                              "status",
                              2,
                              "sessionRequestData",
                              Json.o("appId", "12345", "deviceHashId", "other-device")))));
            });
    SessionClient client = new SessionClient(api);
    Game g = game();
    client.create(g, g.selected());
    assertEquals("fixture-session", client.recoverOrCreate(g, g.selected()).id);
    assertEquals(2, posts[0]);
  }

  @Test
  public void explicitWebsocketBeatsRtspEvenWhenRtspUsesFourteen() throws Exception {
    JSONObject payload = queue(2);
    payload
        .getJSONObject("session")
        .put(
            "connectionInfo",
            Json.a(
                Json.o("usage", 14, "resourcePath", "rtsps://rtsp.nvidiagrid.net:49003/stream"),
                Json.o("usage", 16, "resourcePath", "wss://rtc.nvidiagrid.net:444/nvst/")));
    assertEquals(
        "wss://rtc.nvidiagrid.net:444/nvst/",
        SessionClient.parse(payload, Api.DEFAULT_BASE).signaling);
  }

  @Test
  public void gameLanguageUsesGfnCodesAndSupportedFallback() {
    assertEquals("pt_BR", settings(Collections.singletonMap("language", "pt-BR")).gameLocale());
    assertEquals("id_ID", settings(Collections.singletonMap("language", "id")).gameLocale());
    assertEquals("en_US", settings(Collections.singletonMap("language", "hi-IN")).gameLocale());
  }
}
