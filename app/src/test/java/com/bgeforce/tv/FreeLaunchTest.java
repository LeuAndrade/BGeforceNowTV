package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;
import okhttp3.*;
import okio.Buffer;
import org.json.*;
import org.junit.Test;

public class FreeLaunchTest {
  private Game freeGame() {
    return new Game(
        Json.o(
            "id",
            "free-fixture",
            "title",
            "Free fixture",
            "variants",
            Json.a(
                Json.o(
                    "id",
                    "12345",
                    "appStore",
                    "STEAM",
                    "paymentModels",
                    Json.a(Json.o("__typename", "FreeToPlay")),
                    "gfn",
                    Json.o("library", Json.o("status", "NOT_OWNED"))))));
  }

  private JSONObject body(Request request) throws IOException {
    Buffer data = new Buffer();
    request.body().writeTo(data);
    return Json.parse(data.readUtf8());
  }

  private JSONObject confirmed(String variant) {
    return Json.o("data", Json.o("addOwnedVariant", Json.o("app", Json.o("id", variant))));
  }

  @Test
  public void firstFreeLaunchConfirmsLibraryBeforeLinkedSessionPost() throws Exception {
    List<String> order = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              Request request = chain.request();
              JSONObject data = body(request);
              if (request.url().host().equals("apps.gxn.nvidia.com")) {
                order.add("library");
                assertTrue(
                    Json.s(data, "query")
                        .contains("addOwnedVariant(language:$locale,variantId:$cmsId)"));
                assertEquals("12345", Json.s(Json.obj(data, "variables"), "cmsId"));
                assertFalse(Json.s(Json.obj(data, "variables"), "locale").isEmpty());
                return SessionFlowTest.response(request, 200, confirmed("12345"));
              }
              order.add("session");
              assertTrue(Json.obj(data, "sessionRequestData").optBoolean("accountLinked"));
              return SessionFlowTest.response(request, 200, SessionFlowTest.queue(1));
            });
    Game game = freeGame();
    new SessionClient(api).create(game, game.selected());
    assertEquals(Arrays.asList("library", "session"), order);
  }

  @Test
  public void libraryRejectionPreventsAFalseLinkedSession() throws Exception {
    int[] requests = {0};
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              requests[0]++;
              assertEquals("apps.gxn.nvidia.com", chain.request().url().host());
              return SessionFlowTest.response(
                  chain.request(),
                  200,
                  Json.o("errors", Json.a(Json.o("message", "Fixture rejected"))));
            });
    Game game = freeGame();
    try {
      new SessionClient(api).create(game, game.selected());
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("Fixture rejected"));
    }
    assertEquals(1, requests[0]);
  }

  @Test
  public void differentConfirmedVariantCannotBeUsedToLaunch() throws Exception {
    int[] requests = {0};
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              requests[0]++;
              assertEquals("apps.gxn.nvidia.com", chain.request().url().host());
              return SessionFlowTest.response(chain.request(), 200, confirmed("9876"));
            });
    Game game = freeGame();
    try {
      new SessionClient(api).create(game, game.selected());
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("did not confirm"));
    }
    assertEquals(1, requests[0]);
  }

  @Test
  public void retryDoesNotRepeatAnAlreadyConfirmedLibraryMutation() throws Exception {
    int[] library = {0}, posts = {0};
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              Request request = chain.request();
              if (request.url().host().equals("apps.gxn.nvidia.com")) {
                library[0]++;
                return SessionFlowTest.response(request, 200, confirmed("12345"));
              }
              if (request.method().equals("GET"))
                return SessionFlowTest.response(request, 200, Json.o("sessions", Json.a()));
              posts[0]++;
              assertTrue(Json.obj(body(request), "sessionRequestData").optBoolean("accountLinked"));
              return SessionFlowTest.response(
                  request,
                  posts[0] == 1 ? 500 : 200,
                  posts[0] == 1 ? Json.o("message", "Fixture failure") : SessionFlowTest.queue(1));
            });
    Game game = freeGame();
    SessionClient client = new SessionClient(api);
    try {
      client.create(game, game.selected());
      fail();
    } catch (Api.Failure expected) {
    }
    client.recoverOrCreate(game, game.selected());
    assertEquals(1, library[0]);
    assertEquals(2, posts[0]);
  }

  @Test
  public void aPaidUnownedVariantIsNeverAutomaticallyMarkedOwned() throws Exception {
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              Request request = chain.request();
              assertNotEquals("apps.gxn.nvidia.com", request.url().host());
              assertFalse(
                  Json.obj(body(request), "sessionRequestData").optBoolean("accountLinked"));
              return SessionFlowTest.response(request, 200, SessionFlowTest.queue(1));
            });
    Game game =
        new Game(
            Json.o(
                "id",
                "paid",
                "variants",
                Json.a(
                    Json.o(
                        "id", "12345", "gfn", Json.o("library", Json.o("status", "NOT_OWNED"))))));
    new SessionClient(api).create(game, game.selected());
  }

  @Test
  public void alreadyOwnedFreeVariantDoesNotNeedAnotherLibraryMutation() throws Exception {
    Api api =
        SessionFlowTest.api(
            Api.DEFAULT_BASE,
            Collections.emptyMap(),
            chain -> {
              assertNotEquals("apps.gxn.nvidia.com", chain.request().url().host());
              assertTrue(
                  Json.obj(body(chain.request()), "sessionRequestData")
                      .optBoolean("accountLinked"));
              return SessionFlowTest.response(chain.request(), 200, SessionFlowTest.queue(1));
            });
    Game game = freeGame();
    game.raw
        .getJSONArray("variants")
        .getJSONObject(0)
        .getJSONObject("gfn")
        .getJSONObject("library")
        .put("status", "MANUAL");
    game = new Game(game.raw);
    new SessionClient(api).create(game, game.selected());
  }
}
