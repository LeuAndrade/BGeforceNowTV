package com.bgeforce.tv;

import java.util.*;
import org.json.JSONObject;

/** Bounded technical error text. Never collect request bodies, authorization or cookies. */
final class Diagnostics {
  private Diagnostics() {}

  static String safe(String text) {
    if (text == null) return "";
    String clean =
        text.replaceAll("(?i)(Bearer|GFNJWT)\\s+[^\\s,;]+", "$1 [oculto]")
            .replaceAll("(?i)(x-nv-sessionid\\.)[a-z0-9-]+", "$1[oculto]")
            .replaceAll(
                "(?<![A-Za-z0-9_.-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_.-])", "[token oculto]")
            .replaceAll(
                "(?i)\\b(access_token|id_token|refresh_token|client_token|code|sessionId|pairing_id)[\\s\"'=:/]+[^\\s,;&\"}]+",
                "$1=[oculto]")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email oculto]")
            .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
    return clean.length() > 1600 ? clean.substring(0, 1600) + "…" : clean;
  }

  static String failure(Throwable error) {
    StringBuilder out = new StringBuilder();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = 0; error != null && i < 4 && seen.add(error); i++, error = error.getCause()) {
      if (out.length() > 0) out.append(" ← ");
      out.append(error.getClass().getSimpleName());
      String detail = error.getMessage();
      if (detail != null && !detail.trim().isEmpty()) out.append(": ").append(safe(detail));
    }
    return out.length() == 0 ? "Falha de sinalização" : safe(out.toString());
  }

  static String status(JSONObject status) {
    StringBuilder out = new StringBuilder();
    for (String key :
        new String[] {
          "statusCode",
          "statusDescription",
          "unifiedErrorCode",
          "sessionErrorCode",
          "gfnErrorCode",
          "serverId"
        }) {
      String value = Json.s(status, key);
      if (!value.isEmpty()) {
        if (out.length() > 0) out.append(" · ");
        out.append(key).append('=').append(safe(value));
      }
    }
    return out.toString();
  }

  static String http(int code, JSONObject payload) {
    String status = status(Json.obj(payload, "requestStatus"));
    String detail = Json.first(payload, "error_description", "message", "error");
    String endpoint = Json.s(payload, "diagnosticEndpoint");
    String requestId = Json.s(payload, "diagnosticRequestId");
    return safe(
        "HTTP "
            + code
            + (endpoint.isEmpty() ? "" : " · " + endpoint)
            + (status.isEmpty() ? "" : "\n" + status)
            + (detail.isEmpty() ? "" : "\n" + detail)
            + (requestId.isEmpty() ? "" : "\nrequestId=" + requestId));
  }
}
