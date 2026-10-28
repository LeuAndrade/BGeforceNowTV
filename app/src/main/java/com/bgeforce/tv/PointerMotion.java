package com.bgeforce.tv;

/** Coalesces high-rate mouse samples without losing distance or fraction residuals. UI-owned. */
final class PointerMotion {
  private double dx, dy;
  private boolean absolute;
  private int x, y, width, height;

  void relative(double x, double y) {
    absolute = false;
    dx += x;
    dy += y;
  }

  void absolute(int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    absolute = true;
    dx = dy = 0;
  }

  boolean pending() {
    return absolute || Math.abs(dx) >= 1 || Math.abs(dy) >= 1;
  }

  byte[] drain(InputEncoder encoder, long us) {
    if (absolute) {
      absolute = false;
      return encoder.mouseAbsolute(x, y, width, height, us);
    }
    int x = (int) Math.max(-32768, Math.min(32767, dx));
    int y = (int) Math.max(-32768, Math.min(32767, dy));
    if (x == 0 && y == 0) return null;
    dx -= x;
    dy -= y;
    return encoder.mouse(x, y, us);
  }

  void clear() {
    absolute = false;
    dx = dy = 0;
  }
}
