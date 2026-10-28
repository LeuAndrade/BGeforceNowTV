package com.bgeforce.tv;

import java.util.ArrayDeque;

/** Bounded FIFO: keyboard key-up and UTF-8 chunks must not be discarded by gamepad coalescing. */
final class ReliableInputs {
  private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
  private int bytes;

  synchronized boolean offer(byte[] packet) {
    if (queue.size() >= 128 || bytes + packet.length > 65536) return false;
    queue.addLast(packet);
    bytes += packet.length;
    return true;
  }

  synchronized byte[] poll() {
    byte[] next = queue.pollFirst();
    if (next != null) bytes -= next.length;
    return next;
  }

  synchronized boolean isEmpty() {
    return queue.isEmpty();
  }

  synchronized void clear() {
    queue.clear();
    bytes = 0;
  }
}
