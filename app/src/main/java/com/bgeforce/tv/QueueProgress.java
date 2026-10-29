package com.bgeforce.tv;
/** Displays the current server response, without an artificial countdown or increase filter. */
final class QueueProgress {
  int update(SessionClient.Seat seat) { return seat.ready() ? -1 : seat.queue; }
}
