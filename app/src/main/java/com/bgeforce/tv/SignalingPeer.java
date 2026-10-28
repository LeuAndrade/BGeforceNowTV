package com.bgeforce.tv;

import java.util.UUID;

/** One transport identity per activity/session/endpoint, independent of the CloudMatch RESUME. */
final class SignalingPeer {
  private String session = "", endpoint = "", name = "";

  static final class Attempt {
    final String name;
    final boolean reconnect;

    Attempt(String name, boolean reconnect) {
      this.name = name;
      this.reconnect = reconnect;
    }
  }

  synchronized Attempt next(String sessionId, String signaling) {
    String base = signaling.split("[?#]", 2)[0].replaceAll("/+$", "");
    boolean reconnect = !name.isEmpty() && session.equals(sessionId) && endpoint.equals(base);
    if (!reconnect) {
      session = sessionId;
      endpoint = base;
      name = newName();
    }
    return new Attempt(name, reconnect);
  }

  synchronized void reset() {
    session = endpoint = name = "";
  }

  synchronized void remember(String sessionId, String signaling, String peerName) {
    session = sessionId;
    endpoint = signaling.split("[?#]", 2)[0].replaceAll("/+$", "");
    name = peerName;
  }

  static String newName() {
    return "peer-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
