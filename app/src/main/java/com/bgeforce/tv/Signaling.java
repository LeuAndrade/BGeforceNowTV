package com.bgeforce.tv;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;
import okhttp3.*;
import org.json.*;
import org.webrtc.IceCandidate;

public final class Signaling implements AutoCloseable {
  public interface Listener {
    void offer(String sdp);

    void ice(IceCandidate candidate);

    void error(String message);
  }

  private final Listener listener;
  private final SessionClient.Seat seat;
  private final Settings settings;
  private volatile String name;
  private boolean freshFallback;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final OkHttpClient http;
  private volatile WebSocket socket;
  private int own = 0, remote = 1, ack;
  private volatile boolean closed;
  private boolean connecting, terminal, offered;
  private int generation;
  private ScheduledFuture<?> heartbeat, offerDeadline;
  private volatile boolean opened;
  private volatile int packets;
  private final boolean reconnect;
  private final long offerTimeoutMs;

  public Signaling(Api api, SessionClient.Seat seat, boolean reconnect, Listener l) {
    this(api, seat, reconnect, SignalingPeer.newName(), l);
  }

  public Signaling(
      Api api, SessionClient.Seat seat, boolean reconnect, String peerName, Listener l) {
    this(api, seat, reconnect, peerName, l, 12000);
  }

  Signaling(Api api, SessionClient.Seat seat, boolean reconnect, String peerName, Listener l,
      long offerTimeoutMs) {
    this.offerTimeoutMs = offerTimeoutMs;
    this.reconnect = reconnect;
    this.name = peerName;
    this.seat = seat;
    settings = api.settings;
    listener = l;
    http =
        SessionRoute.signaling(api.http, seat)
            .newBuilder()
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionSpecs(
                Collections.singletonList(
                    new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build()))
            .build();
  }

  public synchronized void connect() {
    if (closed || connecting) return;
    connecting = true;
    connectAttempt(0);
  }

  static Request request(SessionClient.Seat seat, String name, boolean reconnect) {
    String base = seat.signaling.split("[?#]", 2)[0].replaceAll("/+$", "");
    if (!base.endsWith("/sign_in")) base += "/sign_in";
    HttpUrl url =
        HttpUrl.get(base.replaceFirst("^wss://", "https://"))
            .newBuilder()
            .addQueryParameter("peer_id", name)
            .addQueryParameter("version", reconnect ? "3.0" : "2")
            .addQueryParameter("peer_role", "1")
            .addQueryParameter("pairing_id", seat.id)
            .build();
    if (reconnect) url = url.newBuilder().addQueryParameter("reconnect", "1").build();
    return new Request.Builder()
        .url(url)
        .header("Sec-WebSocket-Protocol", "x-nv-sessionid." + seat.id)
        .header("Origin", "https://play.geforcenow.com")
        .header("User-Agent", Api.UA)
        .build();
  }

  private synchronized boolean current(int value) {
    return !closed && !terminal && generation == value;
  }

  private synchronized void connectAttempt(int attempt) {
    if (closed || terminal) return;
    int value = ++generation;
    own = 0;
    remote = 1;
    opened = false;
    packets = 0;
    try {
      socket =
          http.newWebSocket(
              request(seat, name, reconnectAttempt(attempt)),
              new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                  synchronized (Signaling.this) {
                    if (!current(value)) {
                      ws.close(1000, "closed");
                      return;
                    }
                    socket = ws;
                    opened = true;
                    // Use this socket explicitly: a fast callback must not lose peer_info.
                    ws.send(
                        Json.o(
                                "ackid",
                                nextAck(),
                                "peer_info",
                                Json.o(
                                    "browser",
                                    "Chrome",
                                    "browserVersion",
                                    "131",
                                    "connected",
                                    true,
                                    "id",
                                    own,
                                    "name",
                                    name,
                                    "peerRole",
                                    0,
                                    "resolution",
                                    settings.width() + "x" + settings.height(),
                                    "version",
                                    2))
                            .toString());
                    stopHeartbeat();
                    offerDeadline = timer.schedule(() -> failed(value, attempt,
                        new SocketTimeoutException("No SDP offer after WebSocket sign-in"), null),
                        offerTimeoutMs, TimeUnit.MILLISECONDS);
                    heartbeat =
                        timer.scheduleWithFixedDelay(
                            () -> {
                              if (current(value)) ws.send(Json.o("hb", 1).toString());
                            },
                            5,
                            5,
                            TimeUnit.SECONDS);
                  }
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                  if (current(value)) handle(value, attempt, text);
                }

                @Override
                public void onMessage(WebSocket ws, okio.ByteString bytes) {
                  if (current(value)) handle(value, attempt, bytes.utf8());
                }

                @Override
                public void onFailure(WebSocket ws, Throwable error, Response response) {
                  failed(value, attempt, error, response);
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                  ws.close(code, reason);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                  String message = "WebSocket fechado (" + code + "): " + Diagnostics.safe(reason);
                  Throwable error =
                      code == 1000 || code == 1001 || code == 1006 || code == 1012 || code == 1013
                          ? new SocketException(message)
                          : new IOException(message);
                  failed(value, attempt, error, null);
                }
              });
    } catch (RuntimeException error) {
      failed(value, attempt, error, null);
    }
  }

  private synchronized void stopHeartbeat() {
    if (heartbeat != null) heartbeat.cancel(false);
    heartbeat = null;
    if (offerDeadline != null) offerDeadline.cancel(false);
    offerDeadline = null;
  }

  private void failed(int value, int attempt, Throwable error, Response response) {
    final WebSocket previous;
    synchronized (this) {
      if (!current(value)) return;
      stopHeartbeat();
      // A claim can briefly rotate its transport. Retry only WSS, never RESUME/POST/DELETE.
      if (!offered && attempt < 2 && retryable(error, response)) {
        ++generation; // Old callbacks cannot affect the next socket.
        WebSocket stale = socket;
        socket = null;
        if (attempt == 1) { freshFallback = true; name = SignalingPeer.newName(); }
        if (stale != null) stale.cancel();
        timer.schedule(
            () -> connectAttempt(attempt + 1), (attempt + 1) * 1000L, TimeUnit.MILLISECONDS);
        return;
      }
      terminal = true;
      ++generation;
      previous = socket;
      socket = null;
    }
    NetworkCleanup.execute(
        () -> {
          timer.shutdownNow();
          if (previous != null) previous.close(1000, "signaling failed");
        });
    HttpUrl endpoint = HttpUrl.parse(seat.signaling.replaceFirst("^wss://", "https://"));
    listener.error(
        "Sinalização WebRTC · "
            + (response == null ? "rede" : "HTTP " + response.code())
            + " · "
            + Diagnostics.failure(error)
            + "\nservidor="
            + (endpoint == null ? "indisponível" : endpoint.host())
            + " · protocolo="
            + (reconnectAttempt(attempt) ? "3.0" : "2")
            + " · tentativa="
            + (attempt + 1));
  }

  private boolean reconnectAttempt(int attempt) {
    return !freshFallback && (reconnect || attempt > 0);
  }

  synchronized boolean hasOffer() { return offered; }

  String startupState() {
    return "WSS=" + (opened ? "aberto" : "conectando") + " · mensagens=" + packets;
  }

  String peerName() {
    return name;
  }

  private boolean recoverRemovedPeer(int value, int attempt) {
    WebSocket previous;
    synchronized (this) {
      if (!current(value) || offered || freshFallback || attempt >= 2 || !reconnectAttempt(attempt))
        return false;
      freshFallback = true;
      name = SignalingPeer.newName();
      stopHeartbeat();
      ++generation;
      previous = socket;
      socket = null;
      timer.schedule(() -> connectAttempt(attempt + 1), 500, TimeUnit.MILLISECONDS);
    }
    if (previous != null) previous.close(1000, "fresh peer");
    return true;
  }

  static boolean retryable(Throwable error, Response response) {
    if (response != null) {
      int code = response.code();
      return code == 429 || code == 502 || code == 503 || code == 504;
    }
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    boolean network = false;
    for (int i = 0; error != null && i < 4 && seen.add(error); i++, error = error.getCause()) {
      if (error instanceof SSLException || error instanceof ProtocolException) return false;
      if (error instanceof EOFException
          || error instanceof SocketException
          || error instanceof SocketTimeoutException
          || error instanceof UnknownHostException) network = true;
    }
    return network;
  }

  private synchronized int nextAck() {
    return ++ack;
  }

  private void handle(int value, int attempt, String text) {
    try {
      packets++;
      JSONObject p = new JSONObject(text), info = p.optJSONObject("peer_info");
      if (info != null && name.equals(Json.s(info, "name"))) own = info.optInt("id", own);
      if (p.has("ackid") && (info == null || info.optInt("id", -1) != own))
        send(Json.o("ack", p.optInt("ackid")));
      if (p.has("hb")) {
        send(Json.o("hb", p.opt("hb")));
        return;
      }
      if ("peerRemoved".equals(Json.s(p, "error")) && recoverRemovedPeer(value, attempt)) return;
      if (p.has("error")) {
        failed(
            value,
            attempt,
            new IOException("Servidor: " + Diagnostics.safe(Json.s(p, "error"))),
            null);
        return;
      }
      JSONObject msg = p.optJSONObject("peer_msg");
      if (msg == null) return;
      remote = msg.optInt("from", remote);
      String message = Json.s(msg, "msg");
      if (message.equals("BYE")) {
        failed(
            value, attempt, new IOException("O servidor desconectou este transporte (BYE)"), null);
        return;
      }
      JSONObject data = new JSONObject(message);
      if (Json.s(data, "type").equals("offer")) {
        if (Json.s(data, "sdp").trim().isEmpty())
          throw new JSONException("Empty SDP offer");
        synchronized (this) {
          offered = true;
          if (offerDeadline != null) offerDeadline.cancel(false);
          offerDeadline = null;
        }
        listener.offer(Json.s(data, "sdp"));
      } else if (data.has("candidate"))
        listener.ice(
            new IceCandidate(
                Json.s(data, "sdpMid"), data.optInt("sdpMLineIndex"), Json.s(data, "candidate")));
    } catch (JSONException e) {
      failed(value, attempt, new IOException("Invalid signaling message", e), null);
    }
  }

  public void answer(String sdp, String nvst) {
    peer(Json.o("type", "answer", "sdp", sdp, "nvstSdp", nvst));
  }

  public void requestKeyframe() {
    peer(
        Json.o(
            "type",
            "request_keyframe",
            "reason",
            "decoder-output-change",
            "backlogFrames",
            0,
            "attempt",
            1));
  }

  public void candidate(IceCandidate c) {
    if (!c.sdp.toLowerCase(Locale.ROOT).contains(" tcp "))
      peer(Json.o("candidate", c.sdp, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex));
  }

  private void peer(JSONObject p) {
    send(
        Json.o(
            "peer_msg",
            Json.o("from", own, "to", remote, "msg", p.toString()),
            "ackid",
            nextAck()));
  }

  private void send(JSONObject j) {
    WebSocket s = socket;
    if (!closed && s != null) s.send(j.toString());
  }

  @Override
  public void close() {
    final WebSocket previous;
    synchronized (this) {
      if (closed) return;
      closed = true;
      ++generation;
      previous = socket;
      socket = null;
    }
    NetworkCleanup.execute(
        () -> {
          timer.shutdownNow();
          if (previous != null) previous.close(1000, "closed");
        });
  }
}
