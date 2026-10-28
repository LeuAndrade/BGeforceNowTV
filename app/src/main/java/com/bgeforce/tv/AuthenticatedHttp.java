package com.bgeforce.tv;

import java.io.IOException;
import okhttp3.Request;
import org.json.JSONObject;

/** Exactly one retry of an authenticated request, preserving method and replayable body. */
final class AuthenticatedHttp {
  interface Tokens {
    String current() throws IOException;

    void renewRejected(String rejected) throws IOException;
  }

  interface Exchange {
    JSONObject execute(Request request) throws IOException;
  }

  private final Tokens tokens;
  private final Exchange exchange;

  AuthenticatedHttp(Tokens tokens, Exchange exchange) {
    this.tokens = tokens;
    this.exchange = exchange;
  }

  JSONObject execute(Request request, String scheme) throws IOException {
    if (!scheme.equals("GFNJWT") && !scheme.equals("Bearer"))
      throw new IOException("Unknown authorization scheme");
    String token = tokens.current();
    if (token.isEmpty()) throw new IOException("Missing GeForce NOW identity");
    try {
      return exchange.execute(
          request.newBuilder().header("Authorization", scheme + " " + token).build());
    } catch (Api.Failure error) {
      if (error.code != 401) throw error;
      tokens.renewRejected(token);
      String fresh = tokens.current();
      if (fresh.isEmpty()) throw new IOException("Session renewal returned no identity");
      // Do not recursively call execute: a persistent 401 must be shown, not loop forever.
      return exchange.execute(
          request.newBuilder().header("Authorization", scheme + " " + fresh).build());
    }
  }
}
