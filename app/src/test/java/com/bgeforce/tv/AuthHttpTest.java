package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.*;

/** Real HTTP round-trips to a local fixture server; no NVIDIA credentials are involved. */
public class AuthHttpTest {
  private ServerSocket server;
  private ExecutorService workers;
  private OkHttpClient client;
  private String url;
  private final List<String> authorization = Collections.synchronizedList(new ArrayList<>());
  private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger requests = new AtomicInteger();
  private int failures = 1, status = 401;
  private CountDownLatch concurrent;

  @Before
  public void start() throws Exception {
    client = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build();
    workers = Executors.newCachedThreadPool();
    server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    workers.submit(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              workers.submit(() -> serve(socket));
            } catch (IOException ignored) {
            }
        });
    url = "http://127.0.0.1:" + server.getLocalPort() + "/graphql";
  }

  private void serve(Socket socket) {
    try (Socket connection = socket) {
      connection.setSoTimeout(4000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
      reader.readLine();
      String line, auth = "";
      int length = 0;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon < 0) continue;
        String name = line.substring(0, colon), value = line.substring(colon + 1).trim();
        if (name.equalsIgnoreCase("Authorization")) auth = value;
        if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
      }
      char[] content = new char[length];
      int offset = 0;
      while (offset < length) {
        int count = reader.read(content, offset, length - offset);
        if (count < 0) break;
        offset += count;
      }
      int index = requests.incrementAndGet();
      authorization.add(auth);
      bodies.add(new String(content));
      if (concurrent != null && index <= 2) {
        concurrent.countDown();
        concurrent.await(3, TimeUnit.SECONDS);
      }
      int code = index <= failures ? status : 200;
      byte[] body =
          (code == 200
                  ? "{\"data\":{\"games\":[{\"id\":\"fixture\"}]}}"
                  : "{\"error\":\"unauthorized\"}")
              .getBytes(StandardCharsets.UTF_8);
      OutputStream out = connection.getOutputStream();
      out.write(
          ("HTTP/1.1 "
                  + code
                  + " Result\r\nContent-Type: application/json\r\nContent-Length: "
                  + body.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
    } catch (Exception ignored) {
    }
  }

  @After
  public void stop() {
    try {
      server.close();
    } catch (IOException ignored) {
    }
    workers.shutdownNow();
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdownNow();
  }

  private JSONObject exchange(Request request) throws IOException {
    try (Response response = client.newCall(request).execute()) {
      JSONObject body = Json.parse(response.body().string());
      if (!response.isSuccessful()) throw new Api.Failure(response.code(), body);
      return body;
    }
  }

  private static class Tokens implements AuthenticatedHttp.Tokens {
    JSONObject value = Json.o("access_token", "oauth-access", "id_token", "gfn-identity-old");
    int renewals;

    public synchronized String current() {
      return TokenPolicy.gfn(value);
    }

    public synchronized void renewRejected(String rejected) {
      if (current().equals(rejected)) {
        renewals++;
        value = Json.o("access_token", "oauth-access-new", "id_token", "gfn-identity-new");
      }
    }
  }

  private AuthenticatedHttp transport(Tokens tokens) {
    return new AuthenticatedHttp(tokens, this::exchange);
  }

  @Test
  public void loginIdentityLoadsCatalogWithoutUsingOauthAccess() throws Exception {
    failures = 0;
    Tokens tokens = new Tokens();
    JSONObject result = transport(tokens).execute(new Request.Builder().url(url).build(), "GFNJWT");
    assertEquals("GFNJWT gfn-identity-old", authorization.get(0));
    assertTrue(Json.obj(result, "data").has("games"));
    assertEquals(0, tokens.renewals);
  }

  @Test
  public void rejectedIdentityRenewsAndReplaysOriginalPost() throws Exception {
    Tokens tokens = new Tokens();
    String body = "{\"query\":\"library\"}";
    transport(tokens)
        .execute(
            new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build(),
            "GFNJWT");
    assertEquals(
        Arrays.asList("GFNJWT gfn-identity-old", "GFNJWT gfn-identity-new"), authorization);
    assertEquals(Arrays.asList(body, body), bodies);
    assertEquals(1, tokens.renewals);
  }

  @Test
  public void storeLinkingUsesIdentityWithBearerScheme() throws Exception {
    failures = 0;
    transport(new Tokens()).execute(new Request.Builder().url(url).build(), "Bearer");
    assertEquals("Bearer gfn-identity-old", authorization.get(0));
  }

  @Test
  public void persistent401StopsAfterOneRetry() throws Exception {
    failures = 20;
    Tokens tokens = new Tokens();
    try {
      transport(tokens).execute(new Request.Builder().url(url).build(), "GFNJWT");
      fail("Expected 401");
    } catch (Api.Failure error) {
      assertEquals(401, error.code);
    }
    assertEquals(2, requests.get());
    assertEquals(1, tokens.renewals);
  }

  @Test
  public void forbiddenDoesNotTriggerSessionRenewal() throws Exception {
    status = 403;
    Tokens tokens = new Tokens();
    try {
      transport(tokens).execute(new Request.Builder().url(url).build(), "GFNJWT");
      fail("Expected 403");
    } catch (Api.Failure error) {
      assertEquals(403, error.code);
    }
    assertEquals(1, requests.get());
    assertEquals(0, tokens.renewals);
  }

  @Test
  public void concurrent401ResponsesShareOneRotatedIdentity() throws Exception {
    failures = 2;
    concurrent = new CountDownLatch(2);
    Tokens tokens = new Tokens();
    AuthenticatedHttp transport = transport(tokens);
    ExecutorService tasks = Executors.newFixedThreadPool(2);
    try {
      List<Future<JSONObject>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            tasks.submit(
                () -> transport.execute(new Request.Builder().url(url).build(), "GFNJWT")));
      for (Future<JSONObject> future : results)
        assertTrue(future.get(5, TimeUnit.SECONDS).has("data"));
    } finally {
      tasks.shutdownNow();
    }
    assertEquals(4, requests.get());
    assertEquals(1, tokens.renewals);
  }

  @Test
  public void networkFailureKeepsCredentialsAndDoesNotRenew() throws Exception {
    Tokens tokens = new Tokens();
    AuthenticatedHttp transport =
        new AuthenticatedHttp(
            tokens,
            r -> {
              throw new IOException("offline");
            });
    try {
      transport.execute(new Request.Builder().url(url).build(), "GFNJWT");
      fail("Expected offline");
    } catch (IOException expected) {
    }
    assertEquals(0, tokens.renewals);
    assertEquals("gfn-identity-old", tokens.current());
    assertEquals(0, requests.get());
  }
}
