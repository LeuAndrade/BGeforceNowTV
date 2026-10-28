package com.bgeforce.tv;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** OpenNOW protocol adaptation, MIT; see THIRD_PARTY_NOTICES.md and pinned revision. */
public final class Api {
  public static final String VERSION = "2.0.88.129",
      CLIENT = "q61ddeJrVt7O90Nl-P-N7I36yctih4Ml6FyXLrb6j-U",
      BROWSER_CLIENT = "ZU7sPN-miLujMD95LfOQ453IB0AtjM8sMyvgJ9wCXEQ";
  public static final String UA =
      "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
  public static final String DEFAULT_BASE = "https://prod.cloudmatchbeta.nvidiagrid.net/";
  public final OkHttpClient http;
  public final AccountStorage store;
  public final Settings settings;
  private volatile JSONObject session;
  private final Object renewalLock = new Object();
  private volatile int accountGeneration;
  private final AuthenticatedHttp authenticated =
      new AuthenticatedHttp(
          new AuthenticatedHttp.Tokens() {
            public String current() throws IOException {
              return token();
            }

            public void renewRejected(String rejected) throws IOException {
              forceRefresh(rejected);
            }
          },
          this::request);

  public Api(Context c) {
    this(
        new SecureStore(c),
        new Settings(c),
        new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(3, 1, TimeUnit.MINUTES))
            .build());
  }

  Api(AccountStorage store, Settings settings, OkHttpClient http) {
    this.store = store;
    this.settings = settings;
    this.http = http;
  }

  public static final class Failure extends IOException {
    public final int code;
    public final JSONObject payload;

    Failure(int c, JSONObject p) {
      super(message(c, p));
      code = c;
      payload = p;
    }

    private static String message(int c, JSONObject p) {
      return Diagnostics.http(c, p);
    }
  }

  public JSONObject request(Request r) throws IOException {
    return request(r, http);
  }

  private JSONObject request(Request r, OkHttpClient transport) throws IOException {
    try (Response response = transport.newCall(r).execute()) {
      String data =
          response.body() == null
              ? "{}"
              : new String(
                  Catalog.readBytes(response.body().byteStream(), 8 * 1024 * 1024),
                  java.nio.charset.StandardCharsets.UTF_8);
      JSONObject obj;
      try {
        obj = new JSONObject(data);
      } catch (JSONException e) {
        obj = Json.o("message", "The server returned a non-JSON response");
      }
      if (!response.isSuccessful()) {
        try {
          String path =
              r.url().encodedPath().replaceAll("/v2/session/[^/]+", "/v2/session/[sessao]");
          obj.put("diagnosticEndpoint", r.method() + " " + r.url().host() + path);
          String requestId =
              response.header("x-request-id", response.header("x-nv-request-id", ""));
          obj.put("diagnosticRequestId", Diagnostics.safe(requestId));
        } catch (JSONException ignored) {
        }
        throw new Failure(response.code(), obj);
      }
      return obj;
    }
  }

  public static RequestBody json(JSONObject j) {
    return RequestBody.create(j.toString(), MediaType.get("application/json; charset=utf-8"));
  }

  public Request.Builder standard(String url) {
    return new Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "application/json")
        .header("Origin", "https://play.geforcenow.com")
        .header("Referer", "https://play.geforcenow.com/");
  }

  public Headers headers(String token) {
    return new Headers.Builder()
        .add("Authorization", "GFNJWT " + token)
        .add("nv-client-id", "ec7e38d4-03af-4b58-b131-cfb0495903ab")
        .add("nv-client-version", VERSION)
        .add("nv-client-type", "BROWSER")
        .add("nv-client-streamer", "WEBRTC")
        .add("nv-device-os", "ANDROID")
        .add("nv-device-type", "PHONE")
        .build();
  }

  public synchronized boolean restore() throws Exception {
    session = store.read();
    return session != null;
  }

  public synchronized boolean signedIn() {
    return session != null;
  }

  public synchronized String userName() {
    return Json.first(Json.obj(session, "user"), "name", "preferred_username", "email", "sub");
  }

  public synchronized String userId() {
    return Json.first(Json.obj(session, "user"), "sub", "id");
  }

  public static final class AgeRequired extends IOException {
    AgeRequired() {
      super("Confirme a idade da conta para completar o pedido de sessao.");
    }
  }

  public synchronized int requireLaunchAge() throws IOException {
    if (session == null) throw new IOException("Sign in first");
    long now = System.currentTimeMillis();
    int age = AgePolicy.account(Json.obj(session, "user"), now);
    if (age < 0)
      age = AgePolicy.account(claims(Json.s(Json.obj(session, "tokens"), "id_token")), now);
    if (age < 0) age = AgePolicy.saved(session, now);
    if (age < 0) throw new AgeRequired();
    return age;
  }

  public synchronized void confirmLaunchAge(int age) throws IOException {
    if (session == null) throw new IOException("Sign in first");
    if (age < 0 || age > 120) throw new IOException("Idade invalida");
    try {
      JSONObject updated = Json.parse(session.toString());
      updated.put("launchAge", age);
      updated.put("launchAgeConfirmedAt", System.currentTimeMillis());
      store.write(updated);
      session = updated;
    } catch (Exception error) {
      throw new IOException("Cannot save age confirmation", error);
    }
  }

  public synchronized String base() {
    return session == null ? DEFAULT_BASE : session.optString("base", DEFAULT_BASE);
  }

  public synchronized void setBase(String base) throws Exception {
    if (session == null) throw new IOException("Not signed in");
    validateServiceUrl(base);
    session.put("base", base);
    store.write(session);
  }

  public static void validateServiceUrl(String base) throws IOException {
    HttpUrl u = HttpUrl.parse(base);
    if (u == null
        || !u.isHttps()
        || !u.username().isEmpty()
        || !u.password().isEmpty()
        || u.fragment() != null) throw new IOException("Invalid HTTPS service URL");
    String h = u.host();
    if (!(h.endsWith(".nvidiagrid.net")
        || h.endsWith(".geforcenow.com")
        || h.endsWith(".nvidia.com"))) throw new IOException("Untrusted service host");
  }

  public JSONObject providers() throws IOException {
    return request(standard("https://pcs.geforcenow.com/v1/serviceUrls").build());
  }

  public JSONObject authorize(String idp) throws IOException {
    FormBody.Builder b =
        new FormBody.Builder()
            .add("client_id", CLIENT)
            .add("scope", "openid consent email tk_client age")
            .add("device_id", store.deviceId())
            .add("display_name", "BGeforce Now TV");
    if (idp != null && !idp.isEmpty()) b.add("idp_id", idp);
    return request(standard("https://login.nvidia.com/device/authorize").post(b.build()).build());
  }

  public JSONObject pollCode(String code) throws IOException {
    return request(
        standard("https://login.nvidia.com/token")
            .post(
                new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", code)
                    .add("client_id", CLIENT)
                    .build())
            .build());
  }

  public synchronized void accept(JSONObject tokens) throws Exception {
    if (Json.s(tokens, "access_token").isEmpty()) throw new IOException("Missing access token");
    JSONObject user;
    try {
      user =
          request(
              standard("https://login.nvidia.com/userinfo")
                  .header("Authorization", "Bearer " + Json.s(tokens, "access_token"))
                  .build());
    } catch (IOException e) {
      user = claims(Json.s(tokens, "id_token"));
      if (Json.s(user, "sub").isEmpty()) throw e;
    }
    String base = DEFAULT_BASE;
    JSONObject claims = claims(Json.s(tokens, "id_token"));
    String idp = Json.first(claims, "idp_id", "idpId");
    if (idp.isEmpty()) idp = Json.first(user, "idp_id", "idpId");
    try {
      JSONArray pp = Json.arr(Json.obj(providers(), "gfnServiceInfo"), "gfnServiceEndpoints");
      for (int i = 0; i < pp.length(); i++) {
        JSONObject p = pp.optJSONObject(i);
        if (!idp.isEmpty() && idp.equals(Json.s(p, "idpId")))
          base = Json.s(p, "streamingServiceUrl");
      }
    } catch (IOException ignored) {
      /* Retain the existing broker during a transient discovery failure. */
    }
    ensureClientToken(tokens);
    validateServiceUrl(base);
    session =
        Json.o(
            "tokens",
            tokens,
            "expiresAt",
            System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000L,
            "user",
            user,
            "base",
            base);
    store.write(session);
  }

  public static JSONObject claims(String jwt) {
    return TokenPolicy.claims(jwt);
  }

  public String token() throws IOException {
    synchronized (renewalLock) {
      JSONObject saved = session;
      if (saved == null) throw new IOException("Sign in first");
      JSONObject old = Json.obj(saved, "tokens");
      int generation = accountGeneration;
      if (TokenPolicy.needsRefresh(
          old, saved.optLong("expiresAt", 0), System.currentTimeMillis())) {
        try {
          saveRenewed(refresh(old), generation);
        } catch (IOException error) {
          if (TokenPolicy.needsRefresh(
              old, saved.optLong("expiresAt", 0), System.currentTimeMillis() - 120000)) throw error;
        }
      }
      if (session == null || generation != accountGeneration)
        throw new IOException("Account disconnected");
      return TokenPolicy.gfn(Json.obj(session, "tokens"));
    }
  }

  private void forceRefresh(String rejected) throws IOException {
    synchronized (renewalLock) {
      JSONObject saved = session;
      if (saved == null) throw new IOException("Sign in first");
      JSONObject tokens = Json.obj(saved, "tokens");
      int generation = accountGeneration;
      if (!TokenPolicy.gfn(tokens).equals(rejected)) return;
      saveRenewed(refresh(tokens), generation);
    }
  }

  private synchronized void saveRenewed(JSONObject next, int generation) throws IOException {
    if (session == null || generation != accountGeneration)
      throw new IOException("Account disconnected");
    try {
      JSONObject updated = Json.parse(session.toString());
      updated.put("tokens", next);
      updated.put(
          "expiresAt", System.currentTimeMillis() + next.optLong("expires_in", 3600) * 1000L);
      store.write(updated);
      session = updated;
    } catch (Exception e) {
      throw new IOException("Cannot save refreshed session", e);
    }
  }

  public JSONObject authenticated(Request request, String scheme) throws IOException {
    return authenticated.execute(request, scheme);
  }

  JSONObject authenticated(Request request, String scheme, OkHttpClient transport)
      throws IOException {
    if (transport == http) return authenticated(request, scheme);
    return new AuthenticatedHttp(
            new AuthenticatedHttp.Tokens() {
              public String current() throws IOException {
                return token();
              }

              public void renewRejected(String rejected) throws IOException {
                forceRefresh(rejected);
              }
            },
            r -> request(r, transport))
        .execute(request, scheme);
  }

  private void ensureClientToken(JSONObject tokens) {
    try {
      JSONObject r =
          request(
              standard("https://login.nvidia.com/client_token")
                  .header("Origin", "https://nvfile")
                  .header("Authorization", "Bearer " + Json.s(tokens, "access_token"))
                  .build());
      String value = Json.s(r, "client_token");
      if (!value.isEmpty()) tokens.put("client_token", value);
    } catch (Exception ignored) {
      /* Refresh tokens remain an independent renewal mechanism. */
    }
  }

  private JSONObject refresh(JSONObject old) throws IOException {
    IOException last = null;
    Set<String> clients =
        new LinkedHashSet<>(
            Arrays.asList(old.optString("authClientId", CLIENT), CLIENT, BROWSER_CLIENT));
    for (String grant : new String[] {"client_token", "refresh_token"}) {
      String credential = Json.s(old, grant);
      if (credential.isEmpty()) continue;
      for (String client : clients)
        try {
          FormBody.Builder form = new FormBody.Builder().add("client_id", client);
          if (grant.equals("client_token"))
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:client_token")
                .add("client_token", credential)
                .add("sub", userId());
          else form.add("grant_type", "refresh_token").add("refresh_token", credential);
          JSONObject r =
              request(
                  standard("https://login.nvidia.com/token")
                      .header("Origin", "https://nvfile")
                      .post(form.build())
                      .build());
          if (Json.s(r, "access_token").isEmpty())
            throw new IOException("Refresh returned no access token");
          r = TokenPolicy.merge(old, r, System.currentTimeMillis());
          r.put("authClientId", client);
          return r;
        } catch (IOException e) {
          last = e;
        } catch (JSONException e) {
          last = new IOException(e);
        }
    }
    throw last == null ? new IOException("Session expired; sign in again") : last;
  }

  public synchronized void logout() {
    accountGeneration++;
    session = null;
    store.clear();
  }

  public JSONObject gfn(String url, String method, JSONObject body) throws IOException {
    validateServiceUrl(url);
    Request.Builder r = standard(url);
    Headers hh = headers(token());
    for (String name : hh.names()) r.header(name, hh.get(name));
    r.header("x-device-id", store.deviceId());
    if (method.equals("POST")) r.post(json(body));
    else if (method.equals("DELETE")) r.delete();
    return authenticated(r.build(), "GFNJWT");
  }

  public JSONObject graph(String query, JSONObject variables) throws IOException {
    String t = token();
    Request.Builder r = standard("https://apps.gxn.nvidia.com/graphql");
    Headers h = headers(t);
    for (String n : h.names()) r.header(n, h.get(n));
    r.header("nv-client-type", "NATIVE").header("nv-client-streamer", "NVIDIA-CLASSIC");
    JSONObject result =
        authenticated(
            r.post(json(Json.o("query", query, "variables", variables))).build(), "GFNJWT");
    JSONArray errors = Json.arr(result, "errors");
    if (errors.length() > 0)
      throw new IOException("GFN GraphQL: " + Json.s(errors.optJSONObject(0), "message"));
    return result;
  }
}
