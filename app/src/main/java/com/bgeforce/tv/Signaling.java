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
  private boolean modern = true;
  private long receivedAck;
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
  private final ArrayDeque<String> wire = new ArrayDeque<>();
  private boolean namedAssignment;

  public Signaling(Api api, SessionClient.Seat seat, boolean reconnect, Listener l) {
    this(api, seat, reconnect, SignalingPeer.newName(), l);
  }

  public Signaling(
      Api api, SessionClient.Seat seat, boolean reconnect, String peerName, Listener l) {
    this(api, seat, reconnect, peerName, l, 20000);
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
    return request(seat, name, reconnect, true);
  }

  private static Request request(
      SessionClient.Seat seat, String name, boolean reconnect, boolean modern) {
    String base = seat.signaling.split("[?#]", 2)[0].replaceAll("/+$", "");
    if (!base.endsWith("/sign_in")) base += "/sign_in";
    HttpUrl url =
        HttpUrl.get(base.replaceFirst("^wss://", "https://"))
            .newBuilder()
            .addQueryParameter("peer_id", name)
            .addQueryParameter("version", modern ? "3.0" : "2")
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
    // 3.0 fresh -> OpenNOW's legacy 2 fresh -> 3.0 fresh. Protocol != reconnection.
    modern = attempt != 1 || reconnect;
    if (attempt > 0) name = SignalingPeer.newName();
    own = 0;
    remote = 1;
    receivedAck = 0;
    ack = 0;
    opened = false;
    packets = 0;
    namedAssignment = false;
    note("tentativa=" + (attempt + 1) + " protocolo=" + (modern ? "3.0" : "2"));
    try {
      socket =
          http.newWebSocket(
              request(seat, name, reconnectAttempt(attempt), modern),
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
                    note("WebSocket aberto");
                    // Version 3 assigns peer_info on the server. Version 2 requires this
                    // legacy OpenNOW announcement. Sending it on 3 mixes two wire contracts.
                    if (!modern) ws.send(
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
                    offerDeadline = timer.schedule(() -> offerExpired(value, attempt),
                        offerTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!modern) heartbeat =
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

  private synchronized void offerExpired(int value, int attempt) {
    // cancel(false) cannot stop a timeout callback already waiting for this monitor.
    // An SDP accepted first must win that race instead of turning into a terminal timeout.
    if (current(value) && !offered) failed(value, attempt,
        new SocketTimeoutException("No SDP offer after WebSocket sign-in"), null);
  }

  private void failed(int value, int attempt, Throwable error, Response response) {
    final WebSocket previous;
    synchronized (this) {
      if (!current(value)) return;
      stopHeartbeat();
      // A claim can briefly rotate its transport. Retry only WSS, never RESUME/POST/DELETE.
      boolean unsupportedVersion = modern && response != null
          && (response.code() == 400 || response.code() == 404 || response.code() == 426);
      if (!offered && attempt < 2 && (retryable(error, response) || unsupportedVersion)) {
        ++generation; // Old callbacks cannot affect the next socket.
        WebSocket stale = socket;
        socket = null;
        freshFallback = true;
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
            + (modern ? "3.0" : "2")
            + " · tentativa="
            + (attempt + 1) + "\n" + startupState());
  }

  private boolean reconnectAttempt(int attempt) {
    return !freshFallback && reconnect && attempt == 0;
  }

  synchronized boolean hasOffer() { return offered; }

  synchronized String startupState() {
    JSONObject c = seat.signalingConnection;
    return "WSS=" + (opened ? "aberto" : "conectando") + " · mensagens=" + packets
        + " · peer=" + (own > 0 ? "atribuído" : "aguardando")
        + " · ACK=" + receivedAck + " · SDP=" + offered
        + "\ncanal=" + (c == null ? "indefinido" : c.optInt("usage", -1)
            + "/" + c.optInt("appLevelProtocol", -1) + ":" + StreamEndpoint.port(c))
        + " · nome confirmado=" + namedAssignment + " · " + transportHint();
  }

  private String transportHint() {
    JSONObject request = Json.obj(seat.raw, "sessionRequestData");
    boolean webrtc = false;
    JSONArray metadata = Json.arr(request, "metaData");
    for (int i = 0; i < metadata.length(); i++) {
      JSONObject entry = metadata.optJSONObject(i);
      if ("GSStreamerType".equals(Json.s(entry, "key"))
          && "WebRTC".equals(Json.s(entry, "value"))) webrtc = true;
    }
    return "pedido=" + (webrtc ? "WebRTC" : "não informado")
        + " · streamer=" + request.optInt("streamerVersion", -1)
        + " · RTSPS=" + (request.has("secureRTSPSupported")
            ? String.valueOf(request.optBoolean("secureRTSPSupported")) : "?");
  }

  private synchronized void note(String text) {
    if (wire.size() == 12) wire.removeFirst();
    wire.addLast(text);
  }

  synchronized String wireHistory() {
    return "Sinalização (sem corpos/tokens):\n" + Json.join("\n", wire);
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

  private synchronized void handle(int value, int attempt, String text) {
    if (!current(value)) return;
    try {
      if (text.length() > 512 * 1024) throw new JSONException("Signaling message too large");
      packets++;
      JSONObject p = new JSONObject(text), info = p.optJSONObject("peer_info");
      if (info != null) {
        boolean self = name.equals(Json.s(info, "name"));
        if (self) namedAssignment = true;
        note("peer_info self=" + self + " role=" + info.optInt("peerRole", -1)
            + " conectado=" + (info.has("connected")
                ? String.valueOf(info.optBoolean("connected")) : "?")
            + " ackid=" + p.optLong("ackid", -1));
      }
      if (info != null && (name.equals(Json.s(info, "name")) || (modern && own == 0)))
        own = info.optInt("id", own);
      if (p.has("ackid")) {
        long next = p.optLong("ackid", -1);
        if (next < 0) throw new JSONException("Invalid signaling ACK");
        if (modern) {
          // ACK even our own assignment; the server may wait for it before sending SDP.
          send(Json.o("ack", Math.max(receivedAck, next)));
          if (next <= receivedAck) return;
          receivedAck = next;
        } else if (info == null || info.optInt("id", -1) != own) {
          send(Json.o("ack", next));
          receivedAck = Math.max(receivedAck, next);
        }
      }
      if (p.optInt("hb") != 0) {
        if (!modern) send(Json.o("hb", 1));
        return;
      }
      if ("peerRemoved".equals(Json.s(p, "error")) && recoverRemovedPeer(value, attempt)) return;
      if (p.has("error") && !p.isNull("error")) {
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
      if (message.trim().equals("BYE")) {
        failed(
            value, attempt, new IOException("O servidor desconectou este transporte (BYE)"), null);
        return;
      }
      JSONObject data = new JSONObject(message);
      String type = Json.s(data, "type");
      note("peer_msg tipo=" + ("offer".equals(type) ? "oferta"
          : data.has("candidate") ? "ICE" : "outro")
          + " ackid=" + p.optLong("ackid", -1));
      if (Json.s(data, "type").equals("offer")) {
        if (Json.s(data, "sdp").trim().isEmpty() || Json.s(data, "sdp").length() > 256 * 1024)
          throw new JSONException("Empty SDP offer");
        synchronized (this) {
          if (offered) return;
          offered = true;
          if (offerDeadline != null) offerDeadline.cancel(false);
          offerDeadline = null;
        }
        listener.offer(Json.s(data, "sdp"));
      } else if (data.has("candidate")) {
        if (Json.s(data, "candidate").length() > 4096)
          throw new JSONException("ICE candidate too large");
        listener.ice(
            new IceCandidate(
                Json.s(data, "sdpMid"), data.optInt("sdpMLineIndex"), Json.s(data, "candidate")));
      }
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
