package com.bgeforce.tv;

/** Debounces stale queue replicas; a real increase is accepted after two matching reads. */
final class QueueProgress {
  private String session = "";
  private int shown = -1, pending = -1;
  int update(SessionClient.Seat seat) {
    if (!java.util.Objects.equals(seat.id, session)) { session = seat.id; shown = pending = -1; }
    if (seat.ready()) { shown = pending = -1; return -1; }
    int next = seat.queue;
    if (next < 0) { pending = -1; return -1; }
    if (shown < 0 || next <= shown || next == pending) {
      shown = next; pending = -1;
    } else pending = next;
    return shown;
  }
}
