package com.bgeforce.tv;

import static org.junit.Assert.*;

import org.junit.Test;

public class MediaEndpointTest {
  @Test
  public void authorizedMediaEndpointReplacesStalePublicUdpCandidate() {
    String s = "v=0\r\na=candidate:1 1 UDP 2122260223 8.8.8.8 47998 typ host\r\n";
    assertTrue(Sdp.serverEndpoint(s, "9.9.9.9", 48000).contains("9.9.9.9 48000 typ host"));
  }

  @Test
  public void signalingHostWithoutMediaPortCannotReplacePublicMedia() {
    String s = "a=candidate:1 1 UDP 2122260223 8.8.8.8 47998 typ host\r\n";
    assertEquals(s, Sdp.serverEndpoint(s, "9.9.9.9", 0));
  }

  @Test
  public void udpEndpointDoesNotRewritePublicTcpRelay() {
    String s = "a=candidate:1 1 TCP 2122260223 8.8.8.8 443 typ relay tcptype passive\r\n";
    assertEquals(s, Sdp.serverEndpoint(s, "9.9.9.9", 48000));
  }

  @Test
  public void authorizedMediaEndpointDoesNotReplaceAPublicUdpRelay() {
    String s = "a=candidate:1 1 UDP 2122260223 8.8.8.8 443 typ relay\r\n";
    assertEquals(s, Sdp.serverEndpoint(s, "9.9.9.9", 48000));
  }
}
