package com.bgeforce.tv;

/** Buttons are immediate; analog bursts use the latest state once per display interval. */
final class PadChanges {
  private final int[] previous = new int[7];
  private boolean sent;
  private long last;
  private boolean active;

  long waitForAnalog(int buttons, int lt, int rt, int lx, int ly, int rx, int ry, long now) {
    // Never merge press/release edges or delay the final neutral state.
    if (!sent
        || buttons != previous[0]
        || ((lt == 0) != (previous[1] == 0))
        || ((rt == 0) != (previous[2] == 0))
        || (((lx | ly | rx | ry) == 0)
            != ((previous[3] | previous[4] | previous[5] | previous[6]) == 0))) return 0;
    boolean changed =
        lt != previous[1]
            || rt != previous[2]
            || lx != previous[3]
            || ly != previous[4]
            || rx != previous[5]
            || ry != previous[6];
    return changed ? Math.max(0, 16 - (now - last)) : 0;
  }

  boolean accept(int buttons, int lt, int rt, int lx, int ly, int rx, int ry, long now) {
    boolean same =
        previous[0] == buttons
            && previous[1] == lt
            && previous[2] == rt
            && previous[3] == lx
            && previous[4] == ly
            && previous[5] == rx
            && previous[6] == ry;
    active = (buttons | lt | rt | lx | ly | rx | ry) != 0;
    if (sent && same && now - last < delay()) return false;
    previous[0] = buttons;
    previous[1] = lt;
    previous[2] = rt;
    previous[3] = lx;
    previous[4] = ly;
    previous[5] = rx;
    previous[6] = ry;
    last = now;
    sent = true;
    return true;
  }

  long nextDelay(long now) {
    return sent ? Math.max(0, last + delay() - now) : 0;
  }

  long delay() {
    return active ? 100 : 1000;
  }

  void reset() {
    sent = false;
  }
}
