package com.bgeforce.tv;

import java.io.IOException;
import java.util.*;
import okhttp3.*;
import org.json.*;

/** Session lifetime is separate from a renderer, so the queue has a single owner. */
public final class SessionClient {
  private final Api api;
  private final String clientId = UUID.randomUUID().toString();
  private String lastLaunchBase;
  private LaunchIdentity identity;
  private final SessionTrace trace = new SessionTrace();
  private final Set<String> confirmedLibrary = new HashSet<>();
  private final Set<String> abandonedSessions = new HashSet<>();

  public static final class Seat {
    public JSONObject raw;
    public JSONObject signalingConnection;
    public String id, base, signaling = "", ip = "", control = "", mediaIp = "";
    public int mediaPort;
    public int status, queue = -1;
    public boolean adsRequired;
    public boolean resumed;
    public boolean readPinned;
    public int setupStep = -1;
    public String queueBase = "";
    public JSONArray ice = new JSONArray();

    public boolean ready() {
      return (status == 2 || status == 3) && !signaling.isEmpty();
    }
  }

  public SessionClient(Api api) {
    this.api = api;
  }

  public static final class Active {
    public final Seat seat;
    public final String appId;

    Active(Seat seat) {
      this.seat = seat;
      appId = Json.s(Json.obj(seat.raw, "sessionRequestData"), "appId");
    }
  }

  /** The authenticated account, not deviceHashId, owns the list (including other devices). */
  public List<Active> active() throws IOException {
    return active(() -> true);
  }

  interface Allowed {
    boolean get();
  }

  public List<Active> active(Allowed allowed) throws IOException {
    LinkedHashSet<String> bases = new LinkedHashSet<>();
    bases.add(api.settings.get("serverUrl", api.base()));
    bases.add(api.base());
    List<Active> found = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    IOException failure = null;
    boolean queried = false;
    for (String base : bases) {
      if (!allowed.get()) return found;
      try {
        collectActive(base, found, seen);
        queried = true;
      } catch (IOException e) {
        failure = e;
      }
    }
    if (found.isEmpty() && allowed.get()) {
      // A provider root can route to a regional broker. Query server-supplied HTTPS bases only.
      try {
        JSONObject info = api.gfn(api.base().replaceAll("/+$", "") + "/v2/serverInfo", "GET", null);
        JSONArray metadata = Json.arr(info, "metaData");
        int extra = 0;
        for (int i = 0; i < metadata.length() && extra < 6; i++) {
          if (!allowed.get()) return found;
          JSONObject entry = metadata.optJSONObject(i);
          String key = Json.s(entry, "key"), base = Json.s(entry, "value");
          if (key.startsWith("gfn-") || key.isEmpty()) continue;
          try {
            Api.validateServiceUrl(base);
          } catch (IOException ignored) {
            continue;
          }
          if (!bases.add(base)) continue;
          extra++;
          try {
            collectActive(base, found, seen);
            queried = true;
          } catch (IOException e) {
            failure = e;
          }
        }
      } catch (IOException e) {
        failure = e;
      }
    }
    if (!queried && failure != null) throw failure;
    return found;
  }

  public Active findActive(String appId) throws IOException {
    List<Active> found = new ArrayList<>();
    collectActive(api.settings.get("serverUrl", api.base()), found, new HashSet<>());
    for (Active active : found) {
      if (!active.appId.equals(appId)) continue;
      if (waiting(active.seat)) {
        try { return new Active(poll(active.seat)); }
        catch (QueueAbandoned expired) { continue; }
        catch (IOException uncertain) { return active; }
      }
      return active;
    }
    return null;
  }

  private void collectActive(String base, List<Active> out, Set<String> seen) throws IOException {
    JSONArray list = Json.arr(call(base, "/v2/session", "GET", null), "sessions");
    for (int i = 0; i < list.length(); i++) {
      JSONObject raw = list.optJSONObject(i);
      int status = raw == null ? 0 : raw.optInt("status");
      String app = Json.s(Json.obj(raw, "sessionRequestData"), "appId");
      if ((status != 1 && status != 2 && status != 3) || !app.matches("[0-9]+")
          || abandonedSessions.contains(Json.s(raw,"sessionId"))) continue;
      Seat seat = parse(Json.o("session", raw), base);
      if (!seat.id.isEmpty() && seen.add(seat.id)) out.add(new Active(seat));
    }
  }

  /** Claim a validated, still-existing session; never POST/create a replacement on this path. */
  public Seat resume(String base, String id, String appId) throws IOException {
    if (id == null || id.isEmpty() || !appId.matches("[0-9]+"))
      throw new IOException("Invalid session to resume");
    Api.validateServiceUrl(base);
    identity = LaunchIdentity.select(api.base(), "native");
    Seat fresh = parse(call(base, "/v2/session/" + encodedId(id), "GET", null), base);
    if (fresh.status != 1 && fresh.status != 2 && fresh.status != 3)
      throw new IOException("The cloud session is no longer active");
    String actual = Json.s(Json.obj(fresh.raw, "sessionRequestData"), "appId");
    if (!actual.isEmpty() && !actual.equals(appId)) throw new IOException("Session game changed");
    if (fresh.status == 2 || fresh.status == 3) {
      JSONObject body = resumeRequest(fresh, appId);
      LinkedHashSet<String> targets = resumeTargets(fresh);
      IOException error = null;
      boolean claimed = false;
      for (String target : targets) {
        OkHttpClient transport = SessionRoute.claim(api.http, fresh, target);
        HttpUrl u =
            HttpUrl.get(target)
                .newBuilder()
                .encodedPath("/v2/session/" + encodedId(id))
                .query(null)
                .addQueryParameter("keyboardLayout", "en-US")
                .addQueryParameter("languageCode", api.settings.gameLocale())
                .build();
        try {
          call(target, u.encodedPath() + "?" + u.encodedQuery(), "PUT", body, transport);
        } catch (Api.Failure e) {
          // Only an explicit rejected route may be tried at the authenticated broker.
          // A timeout/5xx can be an accepted claim; never repeat that mutation automatically.
          if (e.code != 400 && e.code != 404) throw e;
          error = e;
          continue;
        }
        // Once accepted, a failed read must not cause a second RESUME mutation.
        fresh.base = target;
        fresh.resumed = true;
        try {
          fresh = poll(fresh);
        } catch (IOException e) {
          if (!transientRead(e)) throw e;
          // Accepted mutation, temporarily unavailable GET: let the cancellable activity poll it.
          fresh.status = 1;
          fresh.signaling = "";
        }
        claimed = true;
        break;
      }
      if (!claimed && error != null) throw error;
    }
    fresh.resumed = true;
    lastLaunchBase = fresh.base;
    return fresh;
  }

  static LinkedHashSet<String> resumeTargets(Seat seat) {
    LinkedHashSet<String> targets = new LinkedHashSet<>();
    if (!seat.control.isEmpty()) targets.add(seat.control);
    JSONArray connections = Json.arr(seat.raw, "connectionInfo");
    for (int i = 0; i < connections.length(); i++) {
      JSONObject c = connections.optJSONObject(i);
      if (c == null || c.optInt("usage") != 14) continue;
      String resource = Json.s(c, "resourcePath").replaceFirst("^wss://", "https://");
      for (String url : new String[] {resource, "https://" + ServiceAddress.first(c.opt("ip"))}) {
        try {
          Api.validateServiceUrl(url);
          targets.add(
              HttpUrl.get(url).newBuilder().encodedPath("/").query(null).build().toString());
        } catch (IOException | IllegalArgumentException ignored) {
        }
      }
    }
    if (!seat.control.isEmpty()) targets.add(seat.control);
    targets.add(seat.base);
    return targets;
  }

  JSONObject resumeRequest(Seat seat, String appId) throws IOException {
    JSONObject original = Json.obj(seat.raw, "sessionRequestData");
    LaunchIdentity profile = LaunchIdentity.select(api.base(), "native");
    JSONObject req =
        Json.o(
            "audioMode",
            2,
            "remoteControllersBitmap",
            0,
            "sdrHdrMode",
            0,
            "networkTestSessionId",
            null,
            "availableSupportedControllers",
            Json.a(),
            "clientVersion",
            "30.0",
            "deviceHashId",
            api.store.deviceId(),
            "internalTitle",
            null,
            "clientPlatformName",
            profile.platform,
            "metaData",
            Json.a(
                Json.o("key", "SubSessionId", "value", UUID.randomUUID().toString()),
                Json.o("key", "wssignaling", "value", "1"),
                Json.o("key", "GSStreamerType", "value", "WebRTC"),
                Json.o("key", "networkType", "value", "Unknown"),
                Json.o("key", "ClientImeSupport", "value", "0"),
                Json.o("key", "surroundAudioInfo", "value", "2")),
            "surroundAudioInfo",
            0,
            "clientTimezoneOffset",
            TimeZone.getDefault().getOffset(System.currentTimeMillis()),
            "clientIdentification",
            "GFN-PC",
            "parentSessionId",
            null,
            "appId",
            Long.parseLong(appId),
            "streamerVersion",
            1,
            "appLaunchMode",
            original.optInt("appLaunchMode", 2),
            "sdkVersion",
            "1.0",
            "enhancedStreamMode",
            1,
            "useOps",
            true,
            "clientDisplayHdrCapabilities",
            null,
            "accountLinked",
            true,
            "partnerCustomData",
            "",
            "enablePersistingInGameSettings",
            original.optBoolean("enablePersistingInGameSettings", profile.persistSettings),
            "secureRTSPSupported",
            false,
            "userAge",
            api.requireLaunchAge());
    // Resolution/FPS/codec are immutable on an existing session. Do not include monitor/features.
    return Json.o("action", 2, "data", "RESUME", "sessionRequestData", req, "metaData", Json.a());
  }

  private JSONObject call(String base, String path, String method, JSONObject body)
      throws IOException {
    return call(base, path, method, body, api.http);
  }

  private JSONObject call(
      String base, String path, String method, JSONObject body, OkHttpClient transport)
      throws IOException {
    Api.validateServiceUrl(base);
    Request.Builder r = api.standard(base.replaceAll("/+$", "") + path);
    if (!method.equals("POST") && !method.equals("PUT"))
      r.removeHeader("Origin").removeHeader("Referer");
    Headers h = api.headers(api.token());
    for (String k : h.names()) r.header(k, h.get(k));
    LaunchIdentity profile =
        identity == null ? LaunchIdentity.select(api.base(), api.settings.engine()) : identity;
    r.header("nv-client-id", clientId)
        .header("x-device-id", api.store.deviceId())
        .header("nv-browser-type", "CHROME")
        .header("nv-device-make", "UNKNOWN")
        .header("nv-device-model", "UNKNOWN");
    profile.apply(r);
    if (method.equals("POST")) r.post(Api.json(body));
    if (method.equals("PUT")) r.put(Api.json(body));
    if (method.equals("DELETE")) r.delete();
    if (method.equals("GET")) r.header("Cache-Control","no-cache, no-store").header("Pragma","no-cache");
    JSONObject payload;
    try { payload=api.authenticated(r.build(),"GFNJWT",transport); }
    catch (Api.Failure e) {
      record(method, base, e.payload);
      if (abandoned(e.payload)) throw abandoned(path,e.getMessage());
      throw e;
    } catch (IOException e) {
      trace.record(method, base, -1, -1, -1, "");
      throw e;
    }
    record(method, base, payload);
    JSONObject rs = Json.obj(payload,"requestStatus");
    if(abandoned(payload)) throw abandoned(path,"GFN statusCode=69");
    if (rs.has("statusCode") && rs.optInt("statusCode") != 1)
      throw new IOException(
          "GFN · "
              + method
              + " "
              + HttpUrl.get(base).host()
              + (method.equals("POST") ? "/v2/session" : "/v2/session/[sessao]")
              + " · "
              + Diagnostics.status(rs));
    return payload;
  }

  public Seat create(Game game, Game.Variant variant) throws IOException {
    if (variant == null || !variant.id.matches("[0-9]+"))
      throw new IOException("This game has no numeric launch variant");
    api.requireLaunchAge();
    if (variant.free && !variant.owned && !confirmedLibrary.contains(libraryKey(variant))) {
      Catalog.setOwned(api, variant, true);
      confirmedLibrary.add(libraryKey(variant));
    }
    Settings s = api.settings;
    String base = s.get("serverUrl", api.base());
    Api.validateServiceUrl(base);
    base = resolveLaunchBase(base);
    lastLaunchBase = base;
    identity = LaunchIdentity.select(api.base(), s.engine());
    JSONObject body = buildRequest(variant, base);
    HttpUrl launchUrl =
        HttpUrl.get(base)
            .newBuilder()
            .encodedPath("/v2/session")
            .query(null)
            .addQueryParameter("keyboardLayout", "en-US")
            .addQueryParameter("languageCode", api.settings.gameLocale())
            .build();
    String path = launchUrl.encodedPath() + "?" + launchUrl.encodedQuery();
    Seat fresh = parse(call(base, path, "POST", body), base);
    return fresh.ready() ? assignedSeat(fresh) : fresh;
  }

  /** Only used after an explicit retry, when the first POST may have reached the broker. */
  public Seat recoverOrCreate(Game game, Game.Variant variant) throws IOException {
    if (lastLaunchBase != null && variant != null) {
      JSONObject payload = call(lastLaunchBase, "/v2/session", "GET", null);
      JSONArray active = Json.arr(payload, "sessions");
      for (int i = 0; i < active.length(); i++) {
        JSONObject candidate = active.optJSONObject(i);
        JSONObject request = Json.obj(candidate, "sessionRequestData");
        int status = candidate == null ? 0 : candidate.optInt("status");
        if ((status == 1 || status == 2 || status == 3 || status == 6)
            && !abandonedSessions.contains(Json.s(candidate,"sessionId"))
            && variant.id.equals(Json.s(request, "appId"))
            && api.store.deviceId().equals(Json.s(request, "deviceHashId")))
          return parse(Json.o("session", candidate), lastLaunchBase);
      }
    }
    return create(game, variant);
  }

  static boolean isNvidia(String base) {
    String host = HttpUrl.get(base).host();
    return host.equals("cloudmatchbeta.nvidiagrid.net")
        || host.endsWith(".cloudmatchbeta.nvidiagrid.net")
        || host.equals("cloudmatch.nvidiagrid.net")
        || host.endsWith(".cloudmatch.nvidiagrid.net");
  }

  private String resolveLaunchBase(String base) throws IOException {
    String host = HttpUrl.get(base).host();
    if (!host.startsWith("prod.") || !(host.endsWith(".geforcenow.nvidiagrid.net")
        || isNvidia(base))) return base;
    try {
      JSONObject info = call(base, "/v2/serverInfo", "GET", null);
      return regionalBase(base, Json.arr(info, "metaData"));
    } catch (Api.Failure e) {
      if (e.code == 401 || e.code == 403) throw e;
      return base;
    } catch (IOException unavailable) { return base; }
  }

  static String regionalBase(String base, JSONArray metadata) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    for (int i = 0; i < metadata.length(); i++) {
      JSONObject item = metadata.optJSONObject(i);
      entries.put(Json.s(item, "key").trim(), Json.s(item, "value").trim());
    }
    String local = entries.get("local-region");
    if (local != null && !local.isEmpty()) {
      String supplied = local.startsWith("https://") ? local : entries.get(local);
      String resolved = normalizedBase(supplied);
      if (!resolved.isEmpty()) return resolved;
    }
    Set<String> urls = new LinkedHashSet<>();
    for (Map.Entry<String, String> item : entries.entrySet()) {
      String key = item.getKey();
      if (key.isEmpty() || key.startsWith("gfn-") || key.equals("local-region")) continue;
      String normalized = normalizedBase(item.getValue());
      if (!normalized.isEmpty() && !HttpUrl.get(normalized).host().equals(HttpUrl.get(base).host()))
        urls.add(normalized);
    }
    return urls.size() == 1 ? urls.iterator().next() : base;
  }

  private static String normalizedBase(String value) {
    if (value == null || value.isEmpty()) return "";
    try {
      Api.validateServiceUrl(value);
      return HttpUrl.get(value).newBuilder().encodedPath("/").query(null).build().toString();
    } catch (IOException | IllegalArgumentException invalid) { return ""; }
  }

  private void record(String method, String base, JSONObject payload) {
    JSONObject state = Json.obj(payload, "session");
    trace.record(method, base, Json.obj(payload, "requestStatus").optInt("statusCode", -1),
        state.optInt("status", -1), queue(state), ServiceAddress.control(state));
  }

  public String routeSummary() { return trace.summary(); }
  public String routeHistory() { return trace.details(); }

  JSONObject buildRequest(Game.Variant variant, String base) throws IOException {
    Settings s = api.settings;
    LaunchIdentity profile = LaunchIdentity.select(api.base(), s.engine());
    JSONObject monitor =
        Json.o(
            "widthInPixels",
            s.width(),
            "heightInPixels",
            s.height(),
            "framesPerSecond",
            s.fps(),
            "sdrHdrMode",
            0,
            "displayData",
            null,
            "hdr10PlusGamingData",
            null,
            "dpi",
            0);
    if (profile.desktopMonitor) {
      try {
        monitor.put("monitorId", 0);
        monitor.put("positionX", 0);
        monitor.put("positionY", 0);
        monitor.put("dpi", 100);
      } catch (org.json.JSONException e) {
        throw new IOException(e);
      }
    }
    JSONArray meta =
        Json.a(
            Json.o("key", "SubSessionId", "value", UUID.randomUUID().toString()),
            Json.o("key", "wssignaling", "value", "1"),
            Json.o("key", "GSStreamerType", "value", "WebRTC"),
            Json.o("key", "networkType", "value", "Unknown"),
            Json.o("key", "ClientImeSupport", "value", "0"),
            Json.o("key", "surroundAudioInfo", "value", "2"),
            Json.o(
                "key",
                "clientPhysicalResolution",
                "value",
                Json.o("horizontalPixels", s.width(), "verticalPixels", s.height()).toString()));
    JSONObject features =
        Json.o(
            "reflex",
            false,
            "bitDepth",
            0,
            "cloudGsync",
            false,
            "enabledL4S",
            false,
            "trueHdr",
            false,
            "mouseMovementFlags",
            0,
            "supportedHidDevices",
            0,
            "profile",
            0,
            "fallbackToLogicalResolution",
            false,
            "hidDevices",
            null,
            "chromaFormat",
            0,
            "prefilterMode",
            0,
            "prefilterSharpness",
            0,
            "prefilterNoiseReduction",
            0,
            "hudStreamingMode",
            0,
            "sdrColorSpace",
            2,
            "hdrColorSpace",
            0);
    JSONObject req =
        Json.o(
            "appId",
            variant.id,
            "internalTitle",
            null,
            "availableSupportedControllers",
            Json.a(2),
            "networkTestSessionId",
            null,
            "parentSessionId",
            null,
            "clientIdentification",
            "GFN-PC",
            "deviceHashId",
            api.store.deviceId(),
            "clientVersion",
            "30.0",
            "streamerVersion",
            1,
            "sdkVersion",
            "1.0",
            "enhancedStreamMode",
            1,
            "secureRTSPSupported",
            false,
            "clientPlatformName",
            profile.platform,
            "clientRequestMonitorSettings",
            Json.a(monitor),
            "useOps",
            true,
            "audioMode",
            2,
            "metaData",
            meta,
            "sdrHdrMode",
            0,
            "clientDisplayHdrCapabilities",
            null,
            "surroundAudioInfo",
            0,
            "remoteControllersBitmap",
            1,
            "clientTimezoneOffset",
            TimeZone.getDefault().getOffset(System.currentTimeMillis()),
            "appLaunchMode",
            2,
            "partnerCustomData",
            "",
            "accountLinked",
            variant.owned || confirmedLibrary.contains(libraryKey(variant)),
            "enablePersistingInGameSettings",
            profile.persistSettings,
            "userAge",
            api.requireLaunchAge(),
            "requestedStreamingFeatures",
            features);
    return Json.o("sessionRequestData", req);
  }

  private String libraryKey(Game.Variant variant) {
    return api.userId() + ":" + variant.id;
  }

  public String profileDescription() {
    LaunchIdentity profile =
        identity == null ? LaunchIdentity.select(api.base(), api.settings.engine()) : identity;
    return profile.type + "/" + profile.streamer + "/" + profile.os + "/" + profile.device;
  }

  public Seat poll(Seat previous) throws IOException {
    IOException failure = null;
    for (String base : readTargets(previous)) {
      try {
        OkHttpClient transport = waiting(previous)
            ? api.http.newBuilder().connectTimeout(5,java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8,java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            : SessionRoute.read(api.http,previous,base);
        Seat fresh =
            parse(
                call(base, "/v2/session/" + encodedId(previous.id), "GET", null, transport), base);
        if (!fresh.id.equals(previous.id)) throw new IOException("Session identity changed");
        fresh.resumed=previous.resumed;
        fresh.queueBase = !fresh.control.isEmpty() ? fresh.control
            : previous.queueBase.isEmpty() ? base : previous.queueBase;
        fresh.readPinned=true;
        // The zone can report allocation before its connectionInfo has converged. Resolve once
        // against the authenticated assigned node before handing the seat to either player.
        if (!previous.ready() && fresh.ready()) fresh = assignedSeat(fresh);
        return fresh;
      } catch (IOException e) {
        if (!transientRead(e)) throw e;
        failure = e;
      }
    }
    throw failure == null ? new IOException("No session route") : failure;
  }

  private Seat assignedSeat(Seat zone) throws IOException {
    HttpUrl endpoint = HttpUrl.parse(zone.signaling.replaceFirst("^wss://", "https://"));
    if (endpoint == null || endpoint.host().equals(HttpUrl.get(zone.base).host())) return zone;
    String node = endpoint.newBuilder().port(443).encodedPath("/").query(null).build().toString();
    try { Api.validateServiceUrl(node); } catch (IOException invalid) { return zone; }
    try {
      Seat direct = parse(call(node, "/v2/session/" + encodedId(zone.id), "GET", null,
          SessionRoute.read(api.http, zone, node)), node);
      if (!direct.id.equals(zone.id)) throw new IOException("Session identity changed");
      // A lagging replica must not turn an assigned seat back into its original queue snapshot.
      if (!direct.ready()) return zone;
      direct.resumed=zone.resumed;
      direct.queueBase=zone.queueBase;
      direct.readPinned=true;
      return direct;
    } catch (IOException error) {
      if (!transientRead(error) && !(error instanceof Api.Failure
          && (((Api.Failure) error).code == 404 || ((Api.Failure) error).code == 410))) throw error;
      return zone;
    }
  }

  static LinkedHashSet<String> readTargets(Seat seat) {
    LinkedHashSet<String> targets = new LinkedHashSet<>();
    if(waiting(seat)) {
      String owner = !seat.queueBase.isEmpty() ? seat.queueBase
          : !seat.control.isEmpty() ? seat.control : seat.base;
      try { Api.validateServiceUrl(owner); targets.add(HttpUrl.get(owner).toString()); }
      catch(IOException ignored) { }
      return targets;
    }
    // The last successful read/claim route has priority. A region alias can return 500 for this
    // node.
    for (String target : previousReadOrder(seat)) {
      if (target == null || target.isEmpty()) continue;
      try {
        Api.validateServiceUrl(target);
        targets.add(HttpUrl.get(target).toString());
      } catch (IOException ignored) {
      }
    }
    for (String target : resumeTargets(seat))
      if (targets.size() < 3) targets.add(HttpUrl.get(target).toString());
    return targets;
  }

  private static String[] previousReadOrder(Seat seat) {
    return (seat.resumed || seat.readPinned)
        ? new String[] {seat.base, seat.control}
        : new String[] {seat.control, seat.base};
  }

  static boolean waiting(Seat seat) { return seat.status==1 || seat.status==6 || (seat.status!=2 && seat.status!=3 && seat.setupStep==1); }

  public static final class QueueAbandoned extends IOException {
    QueueAbandoned(String reason) {
      super("A solicitação de fila expirou no servidor (statusCode=69). Selecione Tentar novamente para entrar em uma nova fila.\n"+reason);
    }
  }
  static boolean abandoned(JSONObject payload) {
    JSONObject status=Json.obj(payload,"requestStatus");
    return status.optInt("statusCode",-1)==69
        || "SESSION_REQUEST_IN_QUEUE_ABANDONED".equals(Json.s(status,"statusDescription"));
  }
  private QueueAbandoned abandoned(String path,String reason) {
    HttpUrl u=HttpUrl.parse(Api.DEFAULT_BASE.replaceAll("/+$","")+path);
    if(u!=null && u.pathSegments().size()==3) abandonedSessions.add(u.pathSegments().get(2));
    return new QueueAbandoned(reason);
  }

  static boolean transientRead(Throwable failure) {
    if (failure instanceof Api.Failure) {
      if(abandoned(((Api.Failure)failure).payload)) return false;
      int code = ((Api.Failure) failure).code;
      return code == 408
          || code == 425
          || code == 429
          || code == 500
          || code == 502
          || code == 503
          || code == 504;
    }
    if (failure instanceof javax.net.ssl.SSLException
        || failure instanceof java.net.ProtocolException) return false;
    return failure instanceof java.io.InterruptedIOException
        || failure instanceof java.net.SocketException
        || failure instanceof java.net.SocketTimeoutException
        || failure instanceof java.net.UnknownHostException
        || failure instanceof java.io.EOFException;
  }

  /** Explicit details action: validate this account/session/game before its one DELETE. */
  public void stopActive(Active active) throws IOException {
    Seat fresh;
    try {
      fresh = poll(active.seat);
    } catch (Api.Failure e) {
      if (e.code == 404 || e.code == 410) return;
      throw e;
    }
    String actual = Json.s(Json.obj(fresh.raw, "sessionRequestData"), "appId");
    if (!active.appId.equals(actual))
      throw new IOException("Session game changed; nothing was closed");
    stop(fresh);
  }

  public void stop(Seat seat) throws IOException {
    if (seat != null && !seat.id.isEmpty())
      call(seat.base, "/v2/session/" + encodedId(seat.id), "DELETE", null);
  }

  private static String encodedId(String id) {
    return HttpUrl.get(Api.DEFAULT_BASE)
        .newBuilder()
        .addPathSegment(id)
        .build()
        .encodedPath()
        .substring(1);
  }

  static Seat parse(JSONObject payload, String base) throws IOException {
    JSONObject s = Json.obj(payload, "session");
    if (!s.has("sessionId")) throw new IOException("No session returned");
    Seat seat = new Seat();
    seat.raw = s;
    seat.base=base;
    seat.queueBase=base;
    seat.id = Json.s(s, "sessionId");
    seat.status = s.optInt("status");
    seat.queue = queue(s);
    seat.setupStep = Json.obj(s, "seatSetupInfo").optInt("seatSetupStep", -1);
    seat.adsRequired = s.optBoolean("sessionAdsRequired", s.optBoolean("isAdsRequired", false));
    seat.ice = Json.arr(Json.obj(s, "iceServerConfiguration"), "iceServers");
    if (seat.ice.length() == 0)
      seat.ice =
          Json.a(
              Json.o("urls", "stun:s1.stun.gamestream.nvidia.com:19308"),
              Json.o("urls", "stun:stun.l.google.com:19302"),
              Json.o("urls", "stun:stun1.l.google.com:19302"));
    seat.control = ServiceAddress.control(s);
    // The response's control endpoint owns this session, including while still queued.
    if (!seat.control.isEmpty()) seat.queueBase = seat.control;
    JSONArray cc = Json.arr(s, "connectionInfo");
    JSONObject media = StreamEndpoint.media(cc);
    if (media != null) {
      seat.mediaIp = StreamEndpoint.host(media);
      seat.mediaPort = StreamEndpoint.port(media);
    }
    List<JSONObject> signalingCandidates = new ArrayList<>();
    for (int i = 0; i < cc.length(); i++) {
      JSONObject c = cc.optJSONObject(i);
      if (c != null && (c.optInt("usage") == 14 || c.optInt("usage") == 16))
        signalingCandidates.add(c);
    }
    Collections.sort(signalingCandidates,
        (a, b) -> Integer.compare(StreamEndpoint.priority(a), StreamEndpoint.priority(b)));
    for (JSONObject c : signalingCandidates) {
      String url = StreamEndpoint.signaling(c);
      if (url.isEmpty()) continue;
      HttpUrl u = HttpUrl.parse(url.replaceFirst("^wss://", "https://"));
      if (u == null) continue;
      seat.signaling = url;
      seat.signalingConnection = c;
      String ip = ServiceAddress.first(c.opt("ip"));
      seat.ip = ip.isEmpty() ? u.host() : ip;
      break;
    }
    if (seat.status > 3 && seat.status != 6)
      throw new IOException("The cloud session ended (status " + seat.status + ")");
    return seat;
  }

  static int queue(JSONObject s) {
    int current=queueNumber(s.opt("queuePosition"));
    if(current>=0) return current;
    for(String key:new String[]{"sessionProgress","progressInfo","seatSetupInfo"}) {
      int n=queueNumber(Json.obj(s,key).opt("queuePosition")); if(n>=0)return n;
    }
    return -1;
  }

  private static int queueNumber(Object raw) {
    if (raw == null || raw == JSONObject.NULL || raw instanceof Boolean) return -1;
    try {
      double n = Double.parseDouble(raw.toString());
      return n >= 0 && n <= Integer.MAX_VALUE && n == Math.rint(n) ? (int) n : -1;
    } catch (NumberFormatException e) { return -1; }
  }
}
