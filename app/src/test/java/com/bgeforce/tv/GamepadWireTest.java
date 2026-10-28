package com.bgeforce.tv;

import static org.junit.Assert.*;

import org.junit.Test;

/** Frozen wire fixtures: the input allocation optimization must preserve every protocol byte. */
public class GamepadWireTest {
  private String encoded(int version) {
    InputEncoder encoder = new InputEncoder();
    encoder.version = version;
    byte[] p = encoder.gamepad(0xf00d, 0xd2, 0xe1, -32768, 32767, -1234, 2345, 0x0102030405060708L);
    StringBuilder out = new StringBuilder();
    for (byte b : p) out.append(String.format("%02x", b & 255));
    return out.toString();
  }

  @Test
  public void v2PacketKeepsItsMixedEndianLayout() {
    assertEquals(
        "0c0000001a000000010014000df0d2e10080ff7f2efb29090000550000000807060504030201", encoded(2));
  }

  @Test
  public void v3EnvelopeAndPayloadKeepBothTimestampByteOrders() {
    assertEquals(
        "2301020304050607082100260c0000001a000000010014000df0d2e10080ff7f2efb29090000550000000807060504030201",
        encoded(3));
  }
}
