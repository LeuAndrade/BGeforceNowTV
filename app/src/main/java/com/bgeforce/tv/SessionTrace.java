package com.bgeforce.tv;

import java.util.ArrayDeque;
import okhttp3.HttpUrl;

/** Eight route-only records for an explicit error copy. No IDs, JWTs, bodies or query strings. */
final class SessionTrace {
  interface Clock { long now(); }
  private final Clock clock;
  private final long since;
  private final ArrayDeque<String> entries = new ArrayDeque<>();
  private String lastHost = "—", lastControl = "—", createdHost = "—";
  private int reads;

  SessionTrace() { this(System::nanoTime); }
  SessionTrace(Clock clock) { this.clock = clock; since = clock.now(); }

  private static String host(String url) {
    HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
    return parsed == null ? "—" : parsed.host();
  }

  synchronized void record(String method, String base, int apiCode, int state,
      int queue, String control) {
    // Only our fixed HTTP method names and parsed hosts are kept, never arbitrary error text.
    String verb = method.equals("POST") ? "POST" : method.equals("PUT") ? "PUT"
        : method.equals("DELETE") ? "DELETE" : "GET";
    lastHost = host(base);
    if (verb.equals("POST")) createdHost = lastHost;
    if (control != null && !control.isEmpty()) lastControl = host(control);
    if (verb.equals("GET")) reads++;
    long ms = Math.max(0, (clock.now() - since) / 1000000);
    if (entries.size() == 8) entries.removeFirst();
    entries.addLast("+" + ms + "ms " + verb + " " + lastHost + " api=" + apiCode
        + " estado=" + state + " fila=" + queue + " controle=" + host(control));
  }

  synchronized String summary() {
    return "Criação=" + createdHost + " · rota=" + lastHost + " · controle=" + lastControl + " · leituras=" + reads;
  }

  synchronized String details() { return Json.join("\n", entries); }
}
