package com.bgeforce.tv;

import android.media.*;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import org.webrtc.*;

/** Surface textures avoid copying decoded video into Java/YUV byte arrays. */
final class FastDecoder implements VideoDecoder {
  private final MediaCodecInfo info;
  private final String mime;
  private final EglBase.Context egl;
  private final int fps;
  private final VideoDecoder compatible;
  private MediaCodec codec;
  private SurfaceTextureHelper textures;
  private Surface surface;
  private Thread drain;
  private volatile boolean running, usingCompatible;
  private volatile int width, height;
  private volatile long lastDecodeUs;
  private final ConcurrentHashMap<Long, Long> started = new ConcurrentHashMap<>();
  private Callback callback;

  FastDecoder(
      MediaCodecInfo info, String mime, EglBase.Context egl, int fps, VideoDecoder compatible) {
    this.info = info;
    this.mime = mime;
    this.egl = egl;
    this.fps = fps;
    this.compatible = compatible;
  }

  @Override
  public VideoCodecStatus initDecode(VideoDecoder.Settings s, Callback cb) {
    callback = cb;
    width = s.width;
    height = s.height;
    try {
      textures = SurfaceTextureHelper.create("BGFN-texture", egl);
      if (textures == null) throw new IllegalStateException("EGL texture initialization failed");
      textures.setTextureSize(width, height);
      surface = new Surface(textures.getSurfaceTexture());
      textures.startListening(
          frame -> {
            Long queued = started.remove(frame.getTimestampNs() / 1000);
            Integer ms =
                queued == null
                    ? null
                    : (int) Math.min(1000, (System.nanoTime() / 1000 - queued) / 1000);
            lastDecodeUs = ms == null ? 0 : ms * 1000L;
            if (running) callback.onDecodedFrame(frame, ms, null);
          });
      MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
      if (Build.VERSION.SDK_INT >= 30
          && info.getCapabilitiesForType(mime)
              .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      codec = MediaCodec.createByCodecName(info.getName());
      codec.configure(format, surface, null, 0);
      codec.start();
      running = true;
      drain = new Thread(this::drain, "BGFN-decode-output");
      drain.setPriority(Thread.NORM_PRIORITY + 1);
      drain.start();
      return VideoCodecStatus.OK;
    } catch (Exception e) {
      releaseOwn();
      if (compatible != null) {
        usingCompatible = true;
        return compatible.initDecode(s, cb);
      }
      return VideoCodecStatus.ERROR;
    }
  }

  @Override
  public VideoCodecStatus decode(EncodedImage image, DecodeInfo d) {
    if (usingCompatible) return compatible.decode(image, d);
    if (!running || codec == null) return VideoCodecStatus.UNINITIALIZED;
    if (image.encodedWidth > 0
        && image.encodedHeight > 0
        && (image.encodedWidth != width || image.encodedHeight != height)) {
      // Reconfigure only at a keyframe; do not reserve full 4K adaptive buffers.
      if (image.frameType != EncodedImage.FrameType.VideoFrameKey)
        return VideoCodecStatus.REQUEST_SLI;
      Callback cb = callback;
      releaseOwn();
      VideoCodecStatus status =
          initDecode(new VideoDecoder.Settings(1, image.encodedWidth, image.encodedHeight), cb);
      if (status != VideoCodecStatus.OK) return status;
      // initDecode may have released the custom codec and activated the fallback.
      // Never continue into codec.dequeueInputBuffer when that codec is now null.
      if (usingCompatible) return compatible.decode(image, d);
    }
    try {
      int index = codec.dequeueInputBuffer(0);
      if (index < 0) return VideoCodecStatus.NO_OUTPUT;
      ByteBuffer b = codec.getInputBuffer(index), source = image.buffer.duplicate();
      if (b == null || source.remaining() > b.capacity()) {
        codec.queueInputBuffer(index, 0, 0, 0, 0);
        return VideoCodecStatus.ERR_SIZE;
      }
      b.clear();
      int size = source.remaining();
      b.put(source);
      long pts = image.captureTimeNs / 1000;
      if (started.size() > 16) started.clear();
      started.put(pts, System.nanoTime() / 1000);
      textures.setFrameRotation(image.rotation);
      codec.queueInputBuffer(index, 0, size, pts, 0);
      return VideoCodecStatus.OK;
    } catch (RuntimeException e) {
      return VideoCodecStatus.ERROR;
    }
  }

  private void drain() {
    MediaCodec.BufferInfo b = new MediaCodec.BufferInfo();
    while (running) {
      try {
        int index = codec.dequeueOutputBuffer(b, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat f = codec.getOutputFormat();
          int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
          if (f.containsKey("crop-right") && f.containsKey("crop-left"))
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
          if (f.containsKey("crop-bottom") && f.containsKey("crop-top"))
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
          textures.setTextureSize(w, h);
        } else if (index >= 0) {
          if (textures.isTextureInUse()) {
            started.remove(b.presentationTimeUs);
            codec.releaseOutputBuffer(index, false);
          } else codec.releaseOutputBuffer(index, true);
        }
      } catch (RuntimeException e) {
        running = false;
      }
    }
  }

  @Override
  public VideoCodecStatus release() {
    if (usingCompatible) {
      usingCompatible = false;
      return compatible.release();
    }
    releaseOwn();
    return VideoCodecStatus.OK;
  }

  private void releaseOwn() {
    running = false;
    if (drain != null && Thread.currentThread() != drain) {
      try {
        drain.join(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (drain.isAlive()) return;
      drain = null;
    }
    if (codec != null) {
      try {
        codec.stop();
      } catch (Exception ignored) {
      }
      try {
        codec.release();
      } catch (Exception ignored) {
      }
      codec = null;
    }
    if (textures != null) {
      textures.stopListening();
      textures.dispose();
      textures = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    started.clear();
  }

  @Override
  public String getImplementationName() {
    return usingCompatible ? compatible.getImplementationName() : info.getName() + "/Surface";
  }
}
