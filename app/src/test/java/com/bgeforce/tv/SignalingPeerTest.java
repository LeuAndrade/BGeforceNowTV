package com.bgeforce.tv;

import static org.junit.Assert.*;

import org.junit.Test;

public class SignalingPeerTest {
  @Test
  public void firstConnectionToAnExistingSessionIsFresh() {
    SignalingPeer.Attempt first =
        new SignalingPeer().next("existing", "wss://game.nvidiagrid.net/nvst/");
    assertFalse(first.reconnect);
    assertTrue(first.name.startsWith("peer-"));
  }

  @Test
  public void retryKeepsTheSamePeerAndMarksReconnect() {
    SignalingPeer peers = new SignalingPeer();
    SignalingPeer.Attempt first = peers.next("live", "wss://game.nvidiagrid.net/nvst/");
    SignalingPeer.Attempt next = peers.next("live", "wss://game.nvidiagrid.net/nvst/");
    assertTrue(next.reconnect);
    assertEquals(first.name, next.name);
  }

  @Test
  public void aDifferentEndpointStartsANewTransportIdentity() {
    SignalingPeer peers = new SignalingPeer();
    SignalingPeer.Attempt first = peers.next("live", "wss://old.nvidiagrid.net/nvst/");
    SignalingPeer.Attempt next = peers.next("live", "wss://new.nvidiagrid.net/nvst/");
    assertFalse(next.reconnect);
    assertNotEquals(first.name, next.name);
  }

  @Test
  public void aDifferentCloudSessionStartsFreshEvenOnTheSameHost() {
    SignalingPeer peers = new SignalingPeer();
    SignalingPeer.Attempt first = peers.next("old", "wss://game.nvidiagrid.net/nvst/");
    SignalingPeer.Attempt next = peers.next("new", "wss://game.nvidiagrid.net/nvst/");
    assertFalse(next.reconnect);
    assertNotEquals(first.name, next.name);
  }

  @Test
  public void anotherActivityDoesNotInheritThePreviousDevicesTransport() {
    SignalingPeer.Attempt first =
        new SignalingPeer().next("live", "wss://game.nvidiagrid.net/nvst/");
    SignalingPeer.Attempt next =
        new SignalingPeer().next("live", "wss://game.nvidiagrid.net/nvst/");
    assertFalse(next.reconnect);
    assertNotEquals(first.name, next.name);
  }
}
