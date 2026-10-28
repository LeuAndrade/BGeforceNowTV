package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.webrtc.IceCandidate;

/** Real TLS and WebSocket exchanges; certificates are trusted only by this fixture client. */
public class SignalingWireTest {
  private MockWebServer server;
  private OkHttpClient http;
  private Api api;
  private SessionClient.Seat seat;
  private Signaling signal;
  private String serverError;
  private final CountDownLatch remoteClosed = new CountDownLatch(1);
  private final AtomicReference<WebSocket> remote = new AtomicReference<>();
  private final List<String> offers = new CopyOnWriteArrayList<>(),
      errors = new CopyOnWriteArrayList<>();
  private final CountDownLatch offer = new CountDownLatch(1), error = new CountDownLatch(1);
  private final Signaling.Listener listener =
      new Signaling.Listener() {
        public void offer(String sdp) {
          offers.add(sdp);
          offer.countDown();
        }

        public void ice(IceCandidate c) {}

        public void error(String value) {
          errors.add(value);
          error.countDown();
        }
      };

  @Before
  public void setup() throws Exception {
    HeldCertificate cert =
        new HeldCertificate.Builder().addSubjectAlternativeName("game.nvidiagrid.net").build();
    HandshakeCertificates serverTls =
        new HandshakeCertificates.Builder().heldCertificate(cert).build();
    HandshakeCertificates clientTls =
        new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
    server = new MockWebServer();
    server.useHttps(serverTls.sslSocketFactory(), false);
    server.start();
    http =
        new OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager())
            .dns(host -> Collections.singletonList(InetAddress.getByName("127.0.0.1")))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();
    api = new Api(null, SessionFlowTest.settings(Collections.emptyMap()), http);
    seat = new SessionClient.Seat();
    seat.id = "fixture-session";
    seat.resumed = true;
    seat.signaling = "wss://game.nvidiagrid.net:" + server.getPort() + "/nvst/";
  }

  private MockResponse upgrade(boolean sendOffer) {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              public void onOpen(WebSocket ws, Response response) {
                remote.set(ws);
              }

              public void onMessage(WebSocket ws, String message) {
                if (message.contains("peer_info") && serverError != null) {
                  ws.send(serverError);
                  return;
                }
                if (message.contains("peer_info") && sendOffer)
                  ws.send(
                      Json.o(
                              "peer_msg",
                              Json.o(
                                  "from",
                                  1,
                                  "to",
                                  0,
                                  "msg",
                                  Json.o("type", "offer", "sdp", "v=0\r\n").toString()))
                          .toString());
              }

              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
              }

              public void onClosed(WebSocket ws, int code, String reason) {
                remoteClosed.countDown();
              }
            });
  }

  private void connect() {
    signal = new Signaling(api, seat, false, "peer-fixture", listener);
    signal.connect();
  }

  @After
  public void cleanup() throws Exception {
    if (signal != null) signal.close();
    NetworkCleanup.execute(() -> {}).get(5, TimeUnit.SECONDS);
    WebSocket ws = remote.get();
    if (ws != null) {
      ws.close(1000, "fixture cleanup");
      remoteClosed.await(2, TimeUnit.SECONDS);
    }
    http.connectionPool().evictAll();
    http.dispatcher().executorService().shutdownNow();
    server.shutdown();
  }

  @Test
  public void freshResumeHandshakeReceivesAnOfferOverValidatedTls() throws Exception {
    server.enqueue(upgrade(true));
    connect();
    assertTrue(offer.await(5, TimeUnit.SECONDS));
    RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals("2", r.getRequestUrl().queryParameter("version"));
    assertNull(r.getRequestUrl().queryParameter("reconnect"));
    assertEquals("x-nv-sessionid.fixture-session", r.getHeader("Sec-WebSocket-Protocol"));
    assertNull(r.getHeader("Authorization"));
    assertTrue(errors.isEmpty());
  }

  @Test
  public void transientEofRetriesOnlyTheWebSocketUsingTheSamePeer() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(upgrade(true));
    connect();
    assertTrue(offer.await(8, TimeUnit.SECONDS));
    RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS),
        next = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals("2", first.getRequestUrl().queryParameter("version"));
    assertEquals("3.0", next.getRequestUrl().queryParameter("version"));
    assertEquals("1", next.getRequestUrl().queryParameter("reconnect"));
    assertEquals(
        first.getRequestUrl().queryParameter("peer_id"),
        next.getRequestUrl().queryParameter("peer_id"));
    assertEquals("GET", first.getMethod());
    assertEquals("GET", next.getMethod());
    assertTrue(errors.isEmpty());
    assertEquals(2, server.getRequestCount());
  }

  @Test
  public void forbiddenIsNotRetriedAndNeverProducesABlankMessage() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(403));
    connect();
    assertTrue(error.await(5, TimeUnit.SECONDS));
    assertEquals(1, server.getRequestCount());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("HTTP 403"));
    assertTrue(errors.get(0).contains("ProtocolException"));
    assertTrue(errors.get(0).contains("game.nvidiagrid.net"));
    assertFalse(errors.get(0).contains("fixture-session"));
  }

  @Test
  public void repeatedEofHasABoundedBudgetAndOneFinalError() throws Exception {
    for (int i = 0; i < 3; i++)
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    connect();
    assertTrue(error.await(10, TimeUnit.SECONDS));
    assertEquals(3, server.getRequestCount());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("Exception"));
    assertTrue(errors.get(0).contains("tentativa=3"));
  }

  @Test
  public void explicitCloseCancelsPendingReconnect() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    connect();
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    Field f = Signaling.class.getDeclaredField("timer");
    f.setAccessible(true);
    ScheduledExecutorService timer = (ScheduledExecutorService) f.get(signal);
    signal.close();
    NetworkCleanup.execute(() -> {}).get(5, TimeUnit.SECONDS);
    assertTrue(timer.isShutdown());
    assertNull(server.takeRequest(1300, TimeUnit.MILLISECONDS));
    assertTrue(errors.isEmpty());
  }

  @Test
  public void aCloseAfterTheOfferDoesNotRetryUnderThePreOfferBudget() throws Exception {
    server.enqueue(upgrade(true));
    connect();
    assertTrue(offer.await(5, TimeUnit.SECONDS));
    remote.get().close(1000, "handover");
    assertTrue(error.await(5, TimeUnit.SECONDS));
    assertEquals(1, server.getRequestCount());
    assertEquals(1, errors.size());
  }

  @Test
  public void retryRulesExcludeTlsAuthenticationAndInvalidProtocols() {
    assertFalse(Signaling.retryable(new SSLHandshakeException("certificate"), null));
    assertFalse(Signaling.retryable(new ProtocolException("invalid handshake"), null));
    assertTrue(Signaling.retryable(new EOFException(), null));
    assertTrue(Signaling.retryable(new SocketTimeoutException(), null));
    assertFalse(
        Signaling.retryable(
            new IOException("wrapped", new SSLHandshakeException("certificate")), null));
  }

  @Test
  public void peerRemovedStopsOnceAndKeepsTheActualAttemptNumber() throws Exception {
    serverError = Json.o("error", "peerRemoved").toString();
    server.enqueue(upgrade(false));
    connect();
    assertTrue(error.await(5, TimeUnit.SECONDS));
    assertEquals(1, server.getRequestCount());
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("peerRemoved"));
    assertTrue(errors.get(0).contains("tentativa=1"));
  }

  @Test
  public void anUntrustedTlsCertificateIsRejectedWithoutRetry() throws Exception {
    HeldCertificate unrelated = new HeldCertificate.Builder().build();
    HandshakeCertificates trust =
        new HandshakeCertificates.Builder().addTrustedCertificate(unrelated.certificate()).build();
    http =
        http.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager()).build();
    api = new Api(null, api.settings, http);
    server.enqueue(upgrade(true));
    connect();
    assertTrue(error.await(5, TimeUnit.SECONDS));
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("SSLHandshakeException"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  public void removedReconnectPeerGetsOneFreshHandshakeWithoutClaimingTheSessionAgain()
      throws Exception {
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onMessage(WebSocket ws, String value) {
                    if (value.contains("peer_info")) ws.send("{\"error\":\"peerRemoved\"}");
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));
    server.enqueue(upgrade(true));
    signal = new Signaling(api, seat, true, "peer-removed", listener);
    signal.connect();
    assertTrue(offer.await(6, TimeUnit.SECONDS));
    RecordedRequest old = server.takeRequest(3, TimeUnit.SECONDS),
        fresh = server.takeRequest(3, TimeUnit.SECONDS);
    assertEquals("3.0", old.getRequestUrl().queryParameter("version"));
    assertEquals("2", fresh.getRequestUrl().queryParameter("version"));
    assertNull(fresh.getRequestUrl().queryParameter("reconnect"));
    assertNotEquals(
        old.getRequestUrl().queryParameter("peer_id"),
        fresh.getRequestUrl().queryParameter("peer_id"));
    assertEquals("GET", fresh.getMethod());
    assertTrue(errors.isEmpty());
    assertEquals(2, server.getRequestCount());
  }

  @Test public void silentFreshSocketRecoversWithoutWaitingForTheDecodeWatchdog() throws Exception {
    seat.resumed = false;
    server.enqueue(upgrade(false));
    server.enqueue(upgrade(true));
    signal = new Signaling(api, seat, false, "peer-silent", listener, 300);
    signal.connect();
    assertTrue(offer.await(6, TimeUnit.SECONDS));
    RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("2", first.getRequestUrl().queryParameter("version"));
    assertEquals("3.0", second.getRequestUrl().queryParameter("version"));
    assertTrue(signal.hasOffer());
    assertTrue(errors.isEmpty());
  }

  @Test public void offerDeadlineIsCancelledAfterAnOffer() throws Exception {
    server.enqueue(upgrade(true));
    signal = new Signaling(api, seat, false, "peer-live", listener, 250);
    signal.connect();
    assertTrue(offer.await(3, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
    assertNull(server.takeRequest(700, TimeUnit.MILLISECONDS));
    assertTrue(errors.isEmpty());
  }

  @Test public void silentSocketsHaveAFiniteBudgetAndUseAFreshLastPeer() throws Exception {
    seat.resumed=false;
    for(int i=0;i<3;i++)server.enqueue(upgrade(false));
    signal = new Signaling(api, seat, false, "peer-silent", listener, 200);
    signal.connect();
    assertTrue(error.await(8, TimeUnit.SECONDS));
    RecordedRequest first=server.takeRequest(1,TimeUnit.SECONDS);
    RecordedRequest second=server.takeRequest(1,TimeUnit.SECONDS);
    RecordedRequest third=server.takeRequest(1,TimeUnit.SECONDS);
    assertEquals("3.0",second.getRequestUrl().queryParameter("version"));
    assertEquals("2",third.getRequestUrl().queryParameter("version"));
    assertNotEquals(first.getRequestUrl().queryParameter("peer_id"),third.getRequestUrl().queryParameter("peer_id"));
    assertEquals(3,server.getRequestCount());assertEquals(1,errors.size());
    assertTrue(errors.get(0).contains("No SDP offer"));
  }
}
