package com.bgeforce.tv;

/** FIT geometry shared by physical mouse, touch and remote-control pointer. */
final class VideoViewport {
  final double left, top, width, height;
  final int videoWidth, videoHeight;

  VideoViewport(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
    this.videoWidth = Math.max(1, videoWidth);
    this.videoHeight = Math.max(1, videoHeight);
    double scale =
        Math.min(
            Math.max(1, viewWidth) / (double) this.videoWidth,
            Math.max(1, viewHeight) / (double) this.videoHeight);
    width = this.videoWidth * scale;
    height = this.videoHeight * scale;
    left = (viewWidth - width) / 2;
    top = (viewHeight - height) / 2;
  }

  boolean contains(float x, float y) {
    return x >= left && x < left + width && y >= top && y < top + height;
  }

  int x(float x) {
    return Math.max(0, Math.min(videoWidth - 1, (int) ((x - left) * videoWidth / width)));
  }

  int y(float y) {
    return Math.max(0, Math.min(videoHeight - 1, (int) ((y - top) * videoHeight / height)));
  }
}
