package com.bgeforce.tv;

import java.lang.management.ManagementFactory;
import java.util.HexFormat;

/** JVM allocation count only. This is not an Android/FPS or end-to-end streaming benchmark. */
public final class Allocation {
  static volatile byte[] sink;

  public static void main(String[] args) {
    InputEncoder encoder = new InputEncoder();
    encoder.version = 3;
    String expected =
        "2301020304050607082100260c0000001a000000010014000df0d2e10080ff7f2efb29090000550000000807060504030201";
    String actual =
        HexFormat.of()
            .formatHex(
                encoder.gamepad(
                    0xf00d, 0xd2, 0xe1, -32768, 32767, -1234, 2345, 0x0102030405060708L));
    if (!actual.equals(expected)) throw new AssertionError(actual);
    for (int i = 0; i < 200000; i++) sink = encoder.gamepad(i, 100, 200, -123, 456, 789, -321, i);
    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    long tid = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(tid);
    int n = 250000;
    for (int i = 0; i < n; i++) sink = encoder.gamepad(i, 100, 200, -123, 456, 789, -321, i);
    long allocated = bean.getThreadAllocatedBytes(tid) - before;
    System.out.println(
        "wire_fixture=OK packets="
            + n
            + " allocated_bytes="
            + allocated
            + " bytes_per_packet="
            + ((double) allocated / n));
  }
}
