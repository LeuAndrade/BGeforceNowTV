package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.json.*;
import org.junit.Test;
import org.webrtc.IceCandidate;

/** TLS fixture for the server-assigned peer/ACK handshake observed in GFN signaling 3.0. */
public class SignalingV3RegressionTest {
  @Test public void freshV3AcknowledgesItsOwnAssignmentBeforeReceivingOfferAndAnswering()
      throws Exception {
    MockWebServer server = new MockWebServer();
    HeldCertificate cert = new HeldCertificate.Builder()
        .addSubjectAlternativeName("game.nvidiagrid.net").build();
    HandshakeCertificates st = new HandshakeCertificates.Builder().heldCertificate(cert).build();
    HandshakeCertificates ct = new HandshakeCertificates.Builder()
        .addTrustedCertificate(cert.certificate()).build();
    server.useHttps(st.sslSocketFactory(), false);
    AtomicReference<Signaling> client = new AtomicReference<>();
    AtomicReference<WebSocket> remote = new AtomicReference<>();
    AtomicInteger offers = new AtomicInteger();
    CountDownLatch answer = new CountDownLatch(1), done = new CountDownLatch(1);
    List<String> errors = new CopyOnWriteArrayList<>();
    List<JSONObject> messages = new CopyOnWriteArrayList<>();
    server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        String name = request.getRequestUrl().queryParameter("peer_id");
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
          @Override public void onOpen(WebSocket ws, Response r) {
            remote.set(ws);
            ws.send(Json.o("ackid", 1, "peer_info",
                Json.o("id", 7, "name", name, "connected", true)).toString());
          }
          @Override public void onMessage(WebSocket ws, String text) {
            JSONObject p = Json.parse(text); messages.add(p);
            if (p.optInt("ack", -1) == 1) {
              JSONObject offer = Json.o("ackid", 2, "peer_msg", Json.o("from", 9,
                  "to", 7, "msg", Json.o("type", "offer", "sdp", "v=0\r\n").toString()));
              ws.send(offer.toString());
              ws.send(offer.toString()); // Retransmission must be ACKed, never delivered twice.
            }
            JSONObject peer = p.optJSONObject("peer_msg");
            if (peer != null) {
              JSONObject payload = Json.parse(Json.s(peer, "msg"));
              if ("answer".equals(Json.s(payload, "type"))) {
                if (peer.optInt("from") == 7 && peer.optInt("to") == 9
                    && "attributes".equals(Json.s(payload, "nvstSdp"))) answer.countDown();
              }
            }
          }
          @Override public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(code, reason); done.countDown();
          }
        });
      }
    });
    server.start();
    OkHttpClient http = new OkHttpClient.Builder()
        .sslSocketFactory(ct.sslSocketFactory(), ct.trustManager())
        .dns(h -> Collections.singletonList(InetAddress.getByName("127.0.0.1")))
        .protocols(Collections.singletonList(Protocol.HTTP_1_1)).build();
    SessionClient.Seat seat = SessionClient.parse(Json.o("session", Json.o(
        "sessionId", "fixture-secret-session", "status", 2,
        "connectionInfo", Json.a(Json.o("usage", 14, "appLevelProtocol", 5,
            "ip", "game.nvidiagrid.net", "port", server.getPort(),
            "resourcePath", "/nvst/")))), Api.DEFAULT_BASE);
    Signaling signal = new Signaling(new Api(null,
        SessionFlowTest.settings(Collections.emptyMap()), http), seat, false, "peer-v3",
        new Signaling.Listener() {
          public void offer(String sdp) {
            offers.incrementAndGet(); client.get().answer("local-sdp", "attributes");
          }
          public void ice(IceCandidate candidate) {}
          public void error(String text) { errors.add(text); }
        }, 4000);
    client.set(signal);
    try {
      signal.connect();
      assertTrue("server must receive a routed SDP/NVST answer", answer.await(3, TimeUnit.SECONDS));
      RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
      assertEquals("3.0", request.getRequestUrl().queryParameter("version"));
      assertNull(request.getRequestUrl().queryParameter("reconnect"));
      assertEquals("x-nv-sessionid.fixture-secret-session", request.getHeader("Sec-WebSocket-Protocol"));
      assertNull(request.getHeader("Authorization"));
      assertEquals(1, offers.get());
      assertTrue(errors.toString(), errors.isEmpty());
      assertTrue(messages.stream().anyMatch(p -> p.optInt("ack", -1) == 1));
      assertFalse(messages.stream().anyMatch(p -> p.has("peer_info")));
      assertEquals(server.getPort(), request.getRequestUrl().port());
      String trace = signal.wireHistory();
      assertTrue(trace.contains("peer_info"));
      assertTrue(trace.contains("oferta"));
      assertFalse(trace.contains("fixture-secret-session"));
      assertFalse(trace.contains("peer-v3"));
      assertFalse(trace.contains("v=0"));
    } finally {
      signal.close(); NetworkCleanup.execute(() -> {}).get(3, TimeUnit.SECONDS);
      if (remote.get() != null) remote.get().close(1000, "fixture");
      done.await(1, TimeUnit.SECONDS); http.connectionPool().evictAll();
      http.dispatcher().executorService().shutdownNow(); server.shutdown();
    }
  }
}
