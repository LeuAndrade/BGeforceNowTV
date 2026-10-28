package com.bgeforce.tv;

import java.util.*;
import org.json.*;

/** Strict writes, tolerant reads at optional protocol fields. */
public final class Json {
  private Json() {}

  public static JSONObject o(Object... pairs) {
    JSONObject j = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2)
        j.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return j;
  }

  public static JSONArray a(Object... values) {
    return new JSONArray(Arrays.asList(values));
  }

  public static JSONObject obj(JSONObject j, String k) {
    JSONObject r = j == null ? null : j.optJSONObject(k);
    return r == null ? new JSONObject() : r;
  }

  public static JSONArray arr(JSONObject j, String k) {
    JSONArray r = j == null ? null : j.optJSONArray(k);
    return r == null ? new JSONArray() : r;
  }

  public static String s(JSONObject j, String k) {
    return j == null || j.isNull(k) ? "" : j.optString(k, "");
  }

  public static String first(JSONObject j, String... keys) {
    for (String k : keys) {
      String s = s(j, k);
      if (!s.isEmpty()) return s;
    }
    return "";
  }

  public static String join(String separator, Iterable<String> values) {
    StringBuilder out = new StringBuilder();
    for (String v : values) {
      if (out.length() > 0) out.append(separator);
      out.append(v);
    }
    return out.toString();
  }

  public static JSONObject parse(String s) {
    try {
      return new JSONObject(s);
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }
}
