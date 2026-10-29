package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Buffer;
import org.json.*;
import org.junit.Test;

/** Reference-contract checks are not an authenticated NVIDIA game test. */
public class LaunchContractTest {
  private static final String NVIDIA = Api.DEFAULT_BASE;
  private static final String PARTNER = "https://np-aby-01.geforcenow.nvidiagrid.net/";
  private static final long NOW =
      LocalDate.of(2026, 10, 25).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

  private Api unknownAge(ApiFlowTest.MemoryAccount account) throws Exception {
    Api api =
        new Api(
            account,
            SessionFlowTest.settings(Collections.emptyMap()),
            new OkHttpClient.Builder()
                .addInterceptor(
                    chain -> {
                      throw new AssertionError("Unexpected network call before age confirmation");
                    })
                .build());
    assertTrue(api.restore());
    return api;
  }

  @Test
  public void nativeRendererUsesTheWebRtcAllocationWithTheRealAge() throws Exception {
    AtomicReference<JSONObject> body = new AtomicReference<>();
    Api api =
        SessionFlowTest.api(
            NVIDIA,
            Collections.singletonMap("fps", 60),
            chain -> {
              Request request = chain.request();
              assertEquals("BROWSER", request.header("nv-client-type"));
              assertEquals("WEBRTC", request.header("nv-client-streamer"));
              assertEquals("ANDROID", request.header("nv-device-os"));
              assertEquals("PHONE", request.header("nv-device-type"));
              Buffer buffer = new Buffer();
              request.body().writeTo(buffer);
              body.set(Json.obj(Json.parse(buffer.readUtf8()), "sessionRequestData"));
              return SessionFlowTest.response(request, 200, SessionFlowTest.queue(1));
            });
    Game game = SessionFlowTest.game();
    new SessionClient(api).create(game, game.selected());
    JSONObject data = body.get();
    assertEquals("browser", data.getString("clientPlatformName"));
    assertTrue(data.get("userAge") instanceof Integer);
    assertEquals(37, data.getInt("userAge"));
    assertEquals(1, data.getInt("streamerVersion"));
    assertEquals(1, data.getInt("enhancedStreamMode"));
    assertEquals("1.0", data.getString("sdkVersion"));
    assertFalse(data.getBoolean("secureRTSPSupported"));
    JSONObject monitor = data.getJSONArray("clientRequestMonitorSettings").getJSONObject(0);
    assertFalse(monitor.has("monitorId"));
    assertFalse(monitor.has("positionX"));
    assertFalse(monitor.has("positionY"));
    assertEquals(0, monitor.getInt("dpi"));
    assertEquals(1280, monitor.getInt("widthInPixels"));
    assertEquals(720, monitor.getInt("heightInPixels"));
    assertEquals(60, monitor.getInt("framesPerSecond"));
    boolean webRtc = false;
    JSONArray meta = data.getJSONArray("metaData");
    for (int i = 0; i < meta.length(); i++) {
      JSONObject entry = meta.getJSONObject(i);
      if (entry.getString("key").equals("GSStreamerType"))
        webRtc = entry.getString("value").equals("WebRTC");
    }
    assertTrue(webRtc);
  }

  @Test
  public void rendererMatrixKeepsProviderNativeRequirement() {
    assertEquals(LaunchIdentity.NVIDIA_BROWSER, LaunchIdentity.select(NVIDIA, "native"));
    assertEquals(LaunchIdentity.NVIDIA_BROWSER, LaunchIdentity.select(NVIDIA, "webview"));
    assertEquals(LaunchIdentity.ALLIANCE_NATIVE, LaunchIdentity.select(PARTNER, "native"));
    assertEquals(LaunchIdentity.ALLIANCE_NATIVE, LaunchIdentity.select(PARTNER, "webview"));
  }

  @Test
  public void browserAllocationSurvivesControlHostChange() throws Exception {
    int[] calls = {0};
    Api api =
        SessionFlowTest.api(
            NVIDIA,
            Collections.singletonMap("engine", "webview"),
            chain -> {
              Request request = chain.request();
              calls[0]++;
              assertEquals("BROWSER", request.header("nv-client-type"));
              assertEquals("WEBRTC", request.header("nv-client-streamer"));
              assertEquals("PHONE", request.header("nv-device-type"));
              JSONObject payload = SessionFlowTest.queue(1);
              if (request.method().equals("POST")) {
                try {
                  payload
                      .getJSONObject("session")
                      .put(
                          "sessionControlInfo",
                          Json.o("ip", "assigned.gfn.nvidia.com", "port", 443));
                } catch (JSONException error) {
                  throw new IOException(error);
                }
              } else assertEquals("assigned.gfn.nvidia.com",request.url().host());
              return SessionFlowTest.response(request, 200, payload);
            });
    Game game = SessionFlowTest.game();
    SessionClient client = new SessionClient(api);
    client.poll(client.create(game, game.selected()));
    assertEquals(2, calls[0]);
  }

  @Test
  public void unknownAgeStopsBeforeAnIncompletePost() throws Exception {
    Api api = unknownAge(new ApiFlowTest.MemoryAccount());
    Game game = SessionFlowTest.game();
    try {
      new SessionClient(api).create(game, game.selected());
      fail("Missing age must not be invented");
    } catch (Api.AgeRequired expected) {
    }
  }

  @Test
  public void declaredAgeIsEncryptedThroughTheSameAccountStorageBoundary() throws Exception {
    ApiFlowTest.MemoryAccount account = new ApiFlowTest.MemoryAccount();
    Api api = unknownAge(account);
    api.confirmLaunchAge(16);
    assertEquals(16, api.requireLaunchAge()); // never rewritten to the reference client's 26
    Api restored = unknownAge(account);
    assertEquals(16, restored.requireLaunchAge());
    assertEquals(1, account.writes);
  }

  @Test
  public void authoritativeAccountAgeHasPriority() throws Exception {
    ApiFlowTest.MemoryAccount account = new ApiFlowTest.MemoryAccount();
    account.saved.getJSONObject("user").put("age", 17);
    Api api = unknownAge(account);
    api.confirmLaunchAge(30);
    assertEquals(17, api.requireLaunchAge());
  }

  @Test
  public void loggingOutRemovesAgeConfirmationWithTheAccount() throws Exception {
    ApiFlowTest.MemoryAccount account = new ApiFlowTest.MemoryAccount();
    Api api = unknownAge(account);
    api.confirmLaunchAge(24);
    api.logout();
    assertNull(account.saved);
    Api nextAccount = unknownAge(new ApiFlowTest.MemoryAccount());
    try {
      nextAccount.requireLaunchAge();
      fail();
    } catch (Api.AgeRequired expected) {
    }
  }

  @Test
  public void invalidAgesCannotEnterThePayload() throws Exception {
    Api api = unknownAge(new ApiFlowTest.MemoryAccount());
    for (int age : new int[] {-1, 121, Integer.MAX_VALUE}) {
      try {
        api.confirmLaunchAge(age);
        fail();
      } catch (IOException expected) {
      }
    }
    try {
      api.requireLaunchAge();
      fail();
    } catch (Api.AgeRequired expected) {
    }
  }

  @Test
  public void accountAgeRequiresAnExactValidNumber() {
    for (Object invalid : new Object[] {"18+", "adult", "-1", 121, true, "18.5", ""})
      assertEquals(-1, AgePolicy.number(invalid));
    assertEquals(0, AgePolicy.number(0));
    assertEquals(120, AgePolicy.number("120"));
  }

  @Test
  public void birthdayIsCalculatedWithoutAssumingAnAdultAge() {
    assertEquals(16, AgePolicy.account(Json.o("birthdate", "2010-10-25"), NOW));
    assertEquals(15, AgePolicy.account(Json.o("birthdate", "2010-10-26"), NOW));
    assertEquals(-1, AgePolicy.account(Json.o("birthdate", "2027-01-01"), NOW));
    assertEquals(-1, AgePolicy.account(Json.o("birthdate", "2010-02-30"), NOW));
  }

  @Test
  public void staleConfirmationRequiresAnewCheck() {
    assertEquals(
        20, AgePolicy.saved(Json.o("launchAge", 20, "launchAgeConfirmedAt", NOW - 1000), NOW));
    assertEquals(
        -1,
        AgePolicy.saved(
            Json.o("launchAge", 20, "launchAgeConfirmedAt", NOW - AgePolicy.CONFIRMATION_LIFETIME),
            NOW));
  }

  @Test
  public void diagnosticIncludesMoreServerCodesButNeverAgeFields() {
    String error =
        Diagnostics.status(
            Json.o(
                "statusCode",
                4,
                "statusDescription",
                "INTERNAL_ERROR_STATUS 8A8C0000",
                "unifiedErrorCode",
                0,
                "serverId",
                "NP-MIA-03",
                "sessionErrorCode",
                1,
                "gfnErrorCode",
                2,
                "userAge",
                17,
                "birthdate",
                "2009-01-01"));
    assertTrue(error.contains("8A8C0000"));
    assertTrue(error.contains("NP-MIA-03"));
    assertTrue(error.contains("sessionErrorCode=1"));
    assertTrue(error.contains("gfnErrorCode=2"));
    assertFalse(error.contains("userAge"));
    assertFalse(error.contains("birthdate"));
  }
}
