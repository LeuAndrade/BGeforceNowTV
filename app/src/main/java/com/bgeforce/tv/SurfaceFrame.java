package com.bgeforce.tv;

import java.util.concurrent.atomic.*;
import org.webrtc.VideoFrame;

/**
 * Opaque real MediaCodec output; no fake pixels and no CPU YUV readback. OpenNOW MIT adaptation.
 */
final class SurfaceFrame implements VideoFrame.Buffer {
  private final int width, height;

  interface Finish {
    void accept(boolean render);
  }

  private final Finish finish;
  private final AtomicInteger references = new AtomicInteger(1);
  private final AtomicBoolean finished = new AtomicBoolean();

  SurfaceFrame(int width, int height, Finish finish) {
    this.width = width;
    this.height = height;
    this.finish = finish;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public VideoFrame.I420Buffer toI420() {
    return null;
  }

  public void retain() {
    if (references.incrementAndGet() <= 1)
      throw new IllegalStateException("Released surface frame");
  }

  public void release() {
    if (references.decrementAndGet() == 0 && finished.compareAndSet(false, true))
      finish.accept(false);
  }

  void present() {
    if (finished.compareAndSet(false, true)) finish.accept(true);
  }

  public VideoFrame.Buffer cropAndScale(int x, int y, int w, int h, int outW, int outH) {
    if (x != 0 || y != 0 || w != width || h != height || outW != width || outH != height)
      throw new IllegalArgumentException("Direct surface requires a complete frame");
    retain();
    return this;
  }
}
