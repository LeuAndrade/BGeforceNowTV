package com.bgeforce.tv;

import okio.ByteString;
import org.json.JSONObject;

/** OAuth identity endpoints use access_token; GFN services use id_token when supplied. */
final class TokenPolicy {
  private TokenPolicy() {}

  static String gfn(JSONObject tokens) {
    return Json.first(tokens, "id_token", "access_token");
  }

  static JSONObject claims(String jwt) {
    try {
      String[] parts = jwt.split("\\.");
      if (parts.length != 3) return new JSONObject();
      ByteString decoded = ByteString.decodeBase64(parts[1]);
      return decoded == null ? new JSONObject() : Json.parse(decoded.utf8());
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  static boolean needsRefresh(JSONObject tokens, long oauthExpiry, long now) {
    long expiry = oauthExpiry;
    long identityExpiry = claims(Json.s(tokens, "id_token")).optLong("exp", 0);
    if (identityExpiry > 0) expiry = Math.min(expiry, identityExpiry * 1000L);
    return expiry <= now + 120000;
  }

  static JSONObject merge(JSONObject old, JSONObject next, long now) {
    JSONObject merged = Json.parse(next.toString());
    try {
      for (String key : new String[] {"refresh_token", "client_token", "authClientId"})
        if (Json.s(merged, key).isEmpty() && !Json.s(old, key).isEmpty())
          merged.put(key, old.opt(key));
      if (Json.s(merged, "id_token").isEmpty()) {
        String identity = Json.s(old, "id_token");
        long exp = claims(identity).optLong("exp", 0);
        if (!identity.isEmpty() && (exp == 0 || exp * 1000L > now))
          merged.put("id_token", identity);
      }
      return merged;
    } catch (org.json.JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
