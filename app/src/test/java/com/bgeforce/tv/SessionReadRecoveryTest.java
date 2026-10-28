package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.*;
import org.json.*;
import org.junit.Test;

public class SessionReadRecoveryTest {
  private static final String NODE = "https://node.nvidiagrid.net/",
      ZONE = "https://np-mia-03.cloudmatchbeta.nvidiagrid.net/";

  private SessionClient.Seat seat() throws IOException {
    JSONObject p = SessionFlowTest.queue(3);
    try {
      p.getJSONObject("session")
          .put("sessionRequestData", Json.o("appId", "12345"))
          .put(
              "sessionControlInfo",
              Json.o("ip", "np-mia-03.cloudmatchbeta.nvidiagrid.net", "port", 443));
    } catch (JSONException e) {
      throw new IOException(e);
    }
    SessionClient.Seat seat = SessionClient.parse(p, NODE);
    seat.resumed = true;
    return seat;
  }

  @Test
  public void lastWorkingNodeIsReadBeforeRegionalAlias() throws Exception {
    List<Request> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              Request r = c.request();
              calls.add(r);
              return SessionFlowTest.response(
                  r,
                  r.url().host().equals("node.nvidiagrid.net") ? 200 : 500,
                  SessionFlowTest.queue(3));
            });
    SessionClient.Seat fresh = new SessionClient(api).poll(seat());
    assertEquals(NODE, fresh.base);
    assertTrue(fresh.resumed);
    assertEquals(1, calls.size());
  }

  @Test
  public void read500CanUseAnotherAuthenticatedRouteWithoutMutation() throws Exception {
    List<Request> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              Request r = c.request();
              calls.add(r);
              return SessionFlowTest.response(
                  r,
                  r.url().host().equals("node.nvidiagrid.net") ? 500 : 200,
                  SessionFlowTest.queue(3));
            });
    SessionClient.Seat fresh = new SessionClient(api).poll(seat());
    assertEquals(ZONE, fresh.base);
    assertEquals(2, calls.size());
    for (Request call : calls) assertEquals("GET", call.method());
  }

  @Test
  public void persistent500HasABoundedRouteBudget() throws Exception {
    List<Request> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              calls.add(c.request());
              return SessionFlowTest.response(c.request(), 500, new JSONObject());
            });
    try {
      new SessionClient(api).poll(seat());
      fail();
    } catch (Api.Failure e) {
      assertEquals(500, e.code);
    }
    assertEquals(2, calls.size());
  }

  @Test
  public void notFoundDoesNotReadOtherRoutesOrCreateAReplacement() throws Exception {
    List<Request> calls = new ArrayList<>();
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              calls.add(c.request());
              return SessionFlowTest.response(c.request(), 404, new JSONObject());
            });
    try {
      new SessionClient(api).poll(seat());
      fail();
    } catch (Api.Failure e) {
      assertEquals(404, e.code);
    }
    assertEquals(1, calls.size());
  }

  @Test
  public void explicitCloseChecksGameAndDeletesExactlyOnce() throws Exception {
    List<Request> calls = new ArrayList<>();
    SessionClient.Seat original = seat();
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              calls.add(c.request());
              return SessionFlowTest.response(
                  c.request(),
                  200,
                  c.request().method().equals("DELETE")
                      ? Json.o("requestStatus", Json.o("statusCode", 1))
                      : Json.o("session", original.raw));
            });
    new SessionClient(api).stopActive(new SessionClient.Active(original));
    assertEquals(2, calls.size());
    assertEquals("GET", calls.get(0).method());
    assertEquals("DELETE", calls.get(1).method());
    assertEquals("node.nvidiagrid.net", calls.get(1).url().host());
  }

  @Test
  public void aChangedGameIsNeverDeleted() throws Exception {
    List<Request> calls = new ArrayList<>();
    SessionClient.Seat original = seat(), other = seat();
    other.raw.put("sessionRequestData", Json.o("appId", "99999"));
    Api api =
        SessionFlowTest.api(
            ZONE,
            Collections.emptyMap(),
            c -> {
              calls.add(c.request());
              return SessionFlowTest.response(c.request(), 200, Json.o("session", other.raw));
            });
    try {
      new SessionClient(api).stopActive(new SessionClient.Active(original));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("changed"));
    }
    assertEquals(1, calls.size());
  }

  @Test
  public void readPolicyIncludes500ButExcludesAuthorizationAndTls() {
    assertTrue(SessionClient.transientRead(new Api.Failure(500, new JSONObject())));
    assertTrue(SessionClient.transientRead(new SocketTimeoutException()));
    assertFalse(SessionClient.transientRead(new Api.Failure(403, new JSONObject())));
    assertFalse(SessionClient.transientRead(new SSLHandshakeException("untrusted")));
    assertFalse(SessionClient.transientRead(new ProtocolException()));
  }
}
