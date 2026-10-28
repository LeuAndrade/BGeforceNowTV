package com.bgeforce.tv;

import org.json.JSONObject;

/** Persistence boundary, allowing the real API workflow to run in host tests. */
interface AccountStorage {
  JSONObject read() throws Exception;

  void write(JSONObject value) throws Exception;

  String deviceId();

  void clear();
}
