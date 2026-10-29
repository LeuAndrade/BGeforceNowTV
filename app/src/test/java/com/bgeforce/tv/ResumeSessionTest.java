package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.Handler;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.Buffer;
import org.json.*;
import org.junit.Test;

public class ResumeSessionTest {
  private static final String BROKER = "https://np-aby-01.geforcenow.nvidiagrid.net/";
  private static final String GAME = "https://game.nvidiagrid.net/";

  @Test
  public void inactiveStatusZeroDoesNotWaitForVideoOrMutateTheSession() throws Exception {
    List<String> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              calls.add(chain.request().method());
              return SessionFlowTest.response(
                  chain.request(), 200, payload(raw(0, "inactive", "12345")));
            });
    assertThrows(
        IOException.class, () -> new SessionClient(api).resume(BROKER, "inactive", "12345"));
    assertEquals(Collections.singletonList("GET"), calls);
  }

  @Test
  public void resumedTransportStillRenews401OnceAndPreservesThePutBody() throws Exception {
    AtomicInteger renewals = new AtomicInteger();
    List<String> bodies = new ArrayList<>(), authorizations = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.url().host().equals("login.nvidia.com")) {
                renewals.incrementAndGet();
                return SessionFlowTest.response(
                    r,
                    200,
                    Json.o(
                        "access_token",
                        "oauth-new",
                        "id_token",
                        "identity-new",
                        "refresh_token",
                        "rotated",
                        "expires_in",
                        3600));
              }
              if (r.method().equals("PUT")) {
                bodies.add(body(r).toString());
                authorizations.add(r.header("Authorization"));
                if (bodies.size() == 1)
                  return SessionFlowTest.response(r, 401, Json.o("message", "expired JWT"));
              }
              JSONObject raw = raw(3, "live", "12345");
              try {
                raw.put("connectionInfo", Json.a(Json.o("usage", 14, "ip", "203.0.113.8")));
              } catch (JSONException e) {
                throw new IOException(e);
              }
              return SessionFlowTest.response(r, 200, payload(raw));
            });
    assertTrue(new SessionClient(api).resume(BROKER, "live", "12345").resumed);
    assertEquals(1, renewals.get());
    assertEquals(2, bodies.size());
    assertEquals(bodies.get(0), bodies.get(1));
    assertEquals(Arrays.asList("GFNJWT identity-old", "GFNJWT identity-new"), authorizations);
  }

  private JSONObject raw(int status, String id, String app) {
    return Json.o(
        "sessionId",
        id,
        "status",
        status,
        "queuePosition",
        6,
        "sessionRequestData",
        Json.o(
            "appId",
            app,
            "deviceHashId",
            "other-device",
            "appLaunchMode",
            1,
            "enablePersistingInGameSettings",
            false,
            "clientRequestMonitorSettings",
            Json.a(Json.o("widthInPixels", 1920, "heightInPixels", 1080, "framesPerSecond", 60))),
        "connectionInfo",
        Json.a(
            Json.o(
                "usage",
                14,
                "ip",
                "game.nvidiagrid.net",
                "resourcePath",
                "wss://game.nvidiagrid.net/nvst/")));
  }

  private JSONObject payload(JSONObject raw) {
    return Json.o("requestStatus", Json.o("statusCode", 1), "session", raw);
  }

  private JSONObject body(Request r) throws IOException {
    Buffer b = new Buffer();
    r.body().writeTo(b);
    return Json.parse(b.readUtf8());
  }

  @Test
  public void activeSessionsIncludeOtherDevicesAndExcludeEndedOrInvalidEntries() throws Exception {
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain ->
                SessionFlowTest.response(
                    chain.request(),
                    200,
                    Json.o(
                        "requestStatus",
                        Json.o("statusCode", 1),
                        "sessions",
                        Json.a(
                            raw(1, "q", "100"),
                            raw(2, "ready", "200"),
                            raw(3, "other", "300"),
                            raw(4, "ended", "400"),
                            raw(3, "invalid", "not-an-id")))));
    List<SessionClient.Active> active = new SessionClient(api).active();
    assertEquals(3, active.size());
    assertEquals("300", active.get(2).appId);
    assertEquals(
        "other-device",
        Json.obj(active.get(2).seat.raw, "sessionRequestData").optString("deviceHashId"));
  }

  @Test
  public void stoppedCatalogDoesNotStartSessionDiscoveryRequests() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              requests.incrementAndGet();
              throw new IOException("must not request");
            });
    assertTrue(new SessionClient(api).active(() -> false).isEmpty());
    assertEquals(0, requests.get());
  }

  @Test
  public void resumeClaimsTheRealGameServerOnceAndKeepsImmutableParametersOutOfPut()
      throws Exception {
    List<Request> calls = new ArrayList<>();
    List<JSONObject> puts = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.singletonMap("engine", "web"),
            chain -> {
              Request r = chain.request();
              calls.add(r);
              assertEquals("GFNJWT identity-old", r.header("Authorization"));
              assertEquals("NATIVE", r.header("nv-client-type"));
              assertEquals("NVIDIA-CLASSIC", r.header("nv-client-streamer"));
              if (r.method().equals("PUT")) {
                assertEquals("game.nvidiagrid.net", r.url().host());
                assertEquals("en-US", r.url().queryParameter("keyboardLayout"));
                assertEquals("https://play.geforcenow.com", r.header("Origin"));
                puts.add(body(r));
              } else assertEquals("GET", r.method());
              return SessionFlowTest.response(r, 200, payload(raw(3, "live", "12345")));
            });
    SessionClient.Seat seat = new SessionClient(api).resume(BROKER, "live", "12345");
    assertTrue(seat.resumed);
    assertEquals("live", seat.id);
    assertEquals(GAME, seat.base);
    assertEquals(3, calls.size());
    assertEquals(1, puts.size());
    Set<String> clients = new HashSet<>();
    for (Request r : calls) clients.add(r.header("nv-client-id"));
    assertEquals(1, clients.size());
    JSONObject body = puts.get(0), request = Json.obj(body, "sessionRequestData");
    assertEquals(2, body.optInt("action"));
    assertEquals("RESUME", body.optString("data"));
    assertEquals(37, request.optInt("userAge"));
    assertTrue(request.opt("appId") instanceof Number);
    assertEquals(1, request.optInt("appLaunchMode"));
    assertFalse(request.optBoolean("enablePersistingInGameSettings"));
    assertFalse(request.has("clientRequestMonitorSettings"));
    assertFalse(request.has("requestedStreamingFeatures"));
    assertFalse(request.has("fps"));
    assertFalse(request.has("codec"));
  }

  @Test
  public void queuedSessionIsJoinedWithoutAResumeMutation() throws Exception {
    List<String> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              calls.add(chain.request().method());
              return SessionFlowTest.response(
                  chain.request(), 200, payload(raw(1, "queue", "12345")));
            });
    SessionClient client = new SessionClient(api);
    SessionClient.Seat seat = client.resume(BROKER, "queue", "12345");
    assertEquals(6, seat.queue);
    assertTrue(seat.resumed);
    assertEquals(Collections.singletonList("GET"), calls);
    assertTrue(client.poll(seat).resumed);
  }

  @Test
  public void expiredSessionDoesNotCreateANewGameOrSendPut() throws Exception {
    List<String> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              calls.add(chain.request().method());
              return SessionFlowTest.response(chain.request(), 404, Json.o("message", "expired"));
            });
    assertThrows(
        Api.Failure.class, () -> new SessionClient(api).resume(BROKER, "expired", "12345"));
    assertEquals(Collections.singletonList("GET"), calls);
  }

  @Test
  public void changedGameCannotBeClaimedUnderOldMetadata() throws Exception {
    List<String> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              calls.add(chain.request().method());
              return SessionFlowTest.response(chain.request(), 200, payload(raw(3, "live", "999")));
            });
    assertThrows(IOException.class, () -> new SessionClient(api).resume(BROKER, "live", "12345"));
    assertEquals(Collections.singletonList("GET"), calls);
  }

  @Test
  public void serverErrorAfterAClaimIsNotRetriedAsAnotherMutation() throws Exception {
    AtomicInteger puts = new AtomicInteger();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.method().equals("PUT")) {
                puts.incrementAndGet();
                return SessionFlowTest.response(r, 500, Json.o("message", "temporary"));
              }
              return SessionFlowTest.response(r, 200, payload(raw(3, "live", "12345")));
            });
    assertThrows(Api.Failure.class, () -> new SessionClient(api).resume(BROKER, "live", "12345"));
    assertEquals(1, puts.get());
  }

  @Test
  public void failedReadAfterSuccessfulPutMustNotTriggerAnotherPut() throws Exception {
    AtomicInteger puts = new AtomicInteger();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.method().equals("PUT")) puts.incrementAndGet();
              else if (puts.get() > 0)
                return SessionFlowTest.response(r, 400, Json.o("message", "read failed"));
              return SessionFlowTest.response(r, 200, payload(raw(3, "live", "12345")));
            });
    assertThrows(Api.Failure.class, () -> new SessionClient(api).resume(BROKER, "live", "12345"));
    assertEquals(1, puts.get());
  }

  @Test
  public void explicitRejectedRouteMayFallBackToAuthenticatedBroker() throws Exception {
    List<String> putHosts = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              Request r = chain.request();
              if (r.method().equals("PUT")) {
                putHosts.add(r.url().host());
                if (r.url().host().equals("game.nvidiagrid.net"))
                  return SessionFlowTest.response(r, 400, Json.o("message", "wrong route"));
              }
              return SessionFlowTest.response(r, 200, payload(raw(3, "live", "12345")));
            });
    assertTrue(new SessionClient(api).resume(BROKER, "live", "12345").resumed);
    assertEquals(
        Arrays.asList("game.nvidiagrid.net", "np-aby-01.geforcenow.nvidiagrid.net"), putHosts);
  }

  @Test
  public void resumeUsesExistingResolutionInMemoryWithoutChangingDefaultSettings()
      throws Exception {
    Map<String, Object> prefs = new HashMap<>();
    Settings normal = SessionFlowTest.settings(prefs);
    SessionClient.Seat seat = SessionClient.parse(payload(raw(3, "live", "12345")), BROKER);
    seat.resumed = true;
    Settings effective = normal.forSession(seat);
    assertEquals(1920, effective.width());
    assertEquals(1080, effective.height());
    assertEquals(60, effective.fps());
    assertEquals(1280, normal.width());
    assertEquals(720, normal.height());
    assertEquals(60, normal.fps());
    assertTrue(prefs.isEmpty());
  }

  @Test
  public void directGameIpKeepsNormalTlsIdentityAndAvoidsTheSharedZonePool() throws Exception {
    OkHttpClient normal =
        new OkHttpClient.Builder()
            .dns(host -> Collections.singletonList(InetAddress.getByName("203.0.113.20")))
            .build();
    SessionClient.Seat seat = SessionClient.parse(payload(raw(3, "live", "12345")), BROKER);
    seat.raw.put("connectionInfo", Json.a(Json.o("usage", 14, "ip", "203.0.113.8",
        "resourcePath", "wss://game.nvidiagrid.net/nvst/")));
    OkHttpClient route = SessionRoute.claim(normal, seat, GAME);
    assertNotSame(normal, route);
    assertNotSame(normal.connectionPool(), route.connectionPool());
    assertSame(normal.sslSocketFactory(), route.sslSocketFactory());
    assertSame(normal.hostnameVerifier(), route.hostnameVerifier());
    assertEquals(
        "203.0.113.8", route.dns().lookup(HttpUrl.get(GAME).host()).get(0).getHostAddress());
    assertEquals(
        "203.0.113.20", route.dns().lookup("other.nvidiagrid.net").get(0).getHostAddress());
  }

  @Test
  public void privateGameIpIsNotUsedToRouteAccountCredentials() throws Exception {
    OkHttpClient normal = new OkHttpClient();
    SessionClient.Seat seat = SessionClient.parse(payload(raw(3, "live", "12345")), BROKER);
    for (String ip :
        new String[] {"127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.0.1", "224.0.0.1"}) {
      seat.raw.put("connectionInfo", Json.a(Json.o("usage", 14, "ip", ip)));
      assertSame(normal, SessionRoute.claim(normal, seat, BROKER));
    }
  }

  @Test
  public void untrustedResumeBaseIsRejectedBeforeAnyAuthenticatedRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Api api =
        SessionFlowTest.api(
            BROKER,
            Collections.emptyMap(),
            chain -> {
              calls.incrementAndGet();
              throw new IOException("unexpected request");
            });
    assertThrows(
        IOException.class,
        () -> new SessionClient(api).resume("https://example.com/", "live", "12345"));
    assertEquals(0, calls.get());
  }

  private void field(Object target, String name, Object value) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private void closePlayer(boolean end) throws Exception {
    StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    SessionClient sessions = mock(SessionClient.class);
    SessionClient.Seat seat = new SessionClient.Seat();
    seat.id = "live";
    ExecutorService worker = mock(ExecutorService.class);
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(worker)
        .execute(any(Runnable.class));
    field(activity, "ui", mock(Handler.class));
    field(activity, "images", mock(Images.class));
    field(activity, "sessions", sessions);
    field(activity, "network", worker);
    field(activity, "seat", seat);
    field(activity, "endSession", end);
    Method close = StreamActivity.class.getDeclaredMethod("close");
    close.setAccessible(true);
    close.invoke(activity);
    close.invoke(activity);
    if (end) verify(sessions, times(1)).stop(seat);
    else verify(sessions, never()).stop(any());
    verify(worker, times(1)).shutdown();
  }

  @Test
  public void ordinaryDisconnectKeepsSessionAndIsIdempotent() throws Exception {
    closePlayer(false);
  }

  @Test
  public void explicitEndStopsExactlyOnce() throws Exception {
    closePlayer(true);
  }
}
