package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;

public class ApiFlowTest {
  static class MemoryAccount implements AccountStorage {
    JSONObject saved =
        Json.o(
            "tokens",
            Json.o(
                "access_token",
                "oauth-only",
                "id_token",
                "identity-old",
                "refresh_token",
                "refresh-old"),
            "expiresAt",
            System.currentTimeMillis() + 3600000,
            "user",
            Json.o("sub", "fixture-user"),
            "base",
            Api.DEFAULT_BASE);
    int writes;

    public JSONObject read() {
      return Json.parse(saved.toString());
    }

    public void write(JSONObject value) {
      writes++;
      saved = Json.parse(value.toString());
    }

    public String deviceId() {
      return "fixture-device";
    }

    public void clear() {
      saved = null;
    }
  }

  private Response response(Request request, int code, JSONObject body) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fixture")
        .body(ResponseBody.create(body.toString(), MediaType.get("application/json")))
        .build();
  }

  @Test
  public void actualApiRestoresOldInstallRenews401AndLoadsMetadata() throws Exception {
    MemoryAccount account = new MemoryAccount();
    List<String> graphHeaders = new ArrayList<>();
    AtomicInteger renewalCalls = new AtomicInteger();
    OkHttpClient client =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  Request r = chain.request();
                  if (r.url().host().equals("login.nvidia.com")) {
                    assertEquals("/token", r.url().encodedPath());
                    FormBody form = (FormBody) r.body();
                    assertEquals("refresh_token", form.value(1));
                    renewalCalls.incrementAndGet();
                    return response(
                        r,
                        200,
                        Json.o(
                            "access_token",
                            "oauth-new",
                            "id_token",
                            "identity-new",
                            "refresh_token",
                            "refresh-rotated",
                            "expires_in",
                            3600));
                  }
                  assertEquals("apps.gxn.nvidia.com", r.url().host());
                  graphHeaders.add(r.header("Authorization"));
                  if (graphHeaders.size() == 1)
                    return response(r, 401, Json.o("error", "unauthorized"));
                  assertEquals("GFNJWT identity-new", r.header("Authorization"));
                  return response(
                      r,
                      200,
                      Json.o(
                          "data",
                          Json.o(
                              "apps",
                              Json.o("items", Json.a(Json.o("id", "g1", "title", "Fixture"))))));
                })
            .build();
    Api api = new Api(account, null, client);
    assertTrue(api.restore());
    JSONObject result =
        api.graph("query Fixture { apps { items { id title } } }", new JSONObject());
    assertEquals(Arrays.asList("GFNJWT identity-old", "GFNJWT identity-new"), graphHeaders);
    assertEquals(1, renewalCalls.get());
    assertEquals(1, account.writes);
    assertEquals("refresh-rotated", Json.s(Json.obj(account.saved, "tokens"), "refresh_token"));
    assertEquals(
        "Fixture",
        Json.s(
            Json.arr(Json.obj(Json.obj(result, "data"), "apps"), "items").optJSONObject(0),
            "title"));
    assertEquals("identity-new", api.token());
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdownNow();
  }

  @Test
  public void offlineDoesNotDeleteSavedLogin() throws Exception {
    MemoryAccount account = new MemoryAccount();
    OkHttpClient client =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  throw new java.io.IOException("offline");
                })
            .build();
    Api api = new Api(account, null, client);
    api.restore();
    try {
      api.graph("query Fixture", new JSONObject());
      fail("Expected offline");
    } catch (java.io.IOException expected) {
    }
    assertTrue(api.signedIn());
    assertNotNull(account.saved);
    assertEquals(0, account.writes);
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdownNow();
  }
}
