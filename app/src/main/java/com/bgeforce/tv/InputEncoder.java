package com.bgeforce.tv;

import java.nio.*;

/** Mixed byte orders and v3 framing follow the OpenNOW input protocol. */
public final class InputEncoder {
  public int version = 3;

  public static byte[] heartbeat() {
    return new byte[] {2, 0, 0, 0};
  }

  public byte[] gamepad(int buttons, int lt, int rt, int lx, int ly, int rx, int ry, long us) {
    // Reserve the v3 envelope in the same allocation; do not copy a second packet per input.
    int offset = version <= 2 ? 0 : 12;
    byte[] data = new byte[38 + offset];
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(offset, 12)
        .putShort(offset + 4, (short) 26)
        .putShort(offset + 6, (short) 0)
        .putShort(offset + 8, (short) 1)
        .putShort(offset + 10, (short) 20)
        .putShort(offset + 12, (short) buttons)
        .putShort(offset + 14, (short) ((lt & 255) | ((rt & 255) << 8)))
        .putShort(offset + 16, (short) lx)
        .putShort(offset + 18, (short) ly)
        .putShort(offset + 20, (short) rx)
        .putShort(offset + 22, (short) ry)
        .putShort(offset + 26, (short) 85)
        .putLong(offset + 30, us);
    if (offset != 0) {
      data[0] = 0x23;
      data[9] = 0x21;
      b.order(ByteOrder.BIG_ENDIAN).putLong(1, us).putShort(10, (short) 38);
    }
    return data;
  }

  public byte[] key(boolean down, int vk, int scan, int mods, long us) {
    byte[] p = new byte[18];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(down ? 3 : 4);
    ByteBuffer.wrap(p)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(4, (short) vk)
        .putShort(6, (short) mods)
        .putShort(8, (short) scan)
        .putLong(10, us);
    return single(p, us);
  }

  public byte[] mouse(int dx, int dy, long us) {
    byte[] p = new byte[22];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(7);
    ByteBuffer.wrap(p)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(4, (short) dx)
        .putShort(6, (short) dy)
        .putLong(14, us);
    return batch(p, us);
  }

  public byte[] mouseButton(boolean down, int button, long us) {
    byte[] p = new byte[18];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(down ? 8 : 9);
    p[4] = (byte) button;
    ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN).putLong(10, us);
    return single(p, us);
  }

  /** GFN absolute pointer coordinates, using the video extent (not letterboxed view pixels). */
  public byte[] mouseAbsolute(int x, int y, int width, int height, long us) {
    int w = Math.max(1, Math.min(65535, width)), h = Math.max(1, Math.min(65535, height));
    byte[] p = new byte[26];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(5);
    ByteBuffer.wrap(p)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(4, (short) Math.max(0, Math.min(w - 1, x)))
        .putShort(6, (short) Math.max(0, Math.min(h - 1, y)))
        .putShort(10, (short) w)
        .putShort(12, (short) h)
        .putLong(18, us);
    return batch(p, us);
  }

  public byte[] wheel(int delta, long us) {
    byte[] p = new byte[22];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(10);
    ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN).putShort(6, (short) delta).putLong(14, us);
    return single(p, us);
  }

  /** GFN text packets use their own 0x22/type-23 header, also on protocol v2. */
  public java.util.List<byte[]> text(String value) {
    byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.util.List<byte[]> packets = new java.util.ArrayList<>();
    for (int offset = 0; offset < utf8.length; ) {
      int end = Math.min(utf8.length, offset + 1016);
      while (end < utf8.length && (utf8[end] & 0xc0) == 0x80) end--;
      byte[] packet = new byte[5 + end - offset];
      packet[0] = 0x22;
      ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(1, 23);
      System.arraycopy(utf8, offset, packet, 5, end - offset);
      packets.add(packet);
      offset = end;
    }
    return packets;
  }

  private byte[] single(byte[] p, long us) {
    if (version <= 2) return p;
    byte[] result = new byte[p.length + 10];
    result[0] = 0x23;
    ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putLong(1, us);
    result[9] = 0x22;
    System.arraycopy(p, 0, result, 10, p.length);
    return result;
  }

  private byte[] batch(byte[] p, long us) {
    if (version <= 2) return p;
    byte[] result = new byte[p.length + 12];
    result[0] = 0x23;
    ByteBuffer.wrap(result)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(1, us)
        .putShort(10, (short) p.length);
    result[9] = 0x21;
    System.arraycopy(p, 0, result, 12, p.length);
    return result;
  }
}
