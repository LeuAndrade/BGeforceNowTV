package com.bgeforce.tv;

import android.media.*;
import android.os.*;
import android.util.LongSparseArray;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;
import org.webrtc.*;

/** Bounded async MediaCodec -> SurfaceView output, adapted from OpenNOW's MIT surface path. */
final class SurfaceDecoder implements VideoDecoder {
  private final Object lock = new Object();
  private final MediaCodecInfo info;
  private final String mime;
  private final int fps;
  private final boolean lowLatency;

  interface Target {
    Surface get();
  }

  private final Target target;
  private final VideoDecoder fallback;
  private final Runnable switchOutput, firstFrame;
  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private final ArrayDeque<Integer> inputs = new ArrayDeque<>();
  private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
  private final LongSparseArray<Pending> inCodec = new LongSparseArray<>();
  private MediaCodec codec;
  private HandlerThread thread;
  private Callback callback;
  private volatile boolean compatible, failed;
  private int generation, width, height, outputWidth, outputHeight, queuedBytes;
  private long nextPts;
  private boolean reportedFirst;

  private static final class Pending {
    ByteBuffer data;
    final long timestamp, queued;
    final int rotation, size;

    Pending(EncodedImage image, ByteBuffer copy) {
      data = copy;
      timestamp = image.captureTimeNs;
      queued = SystemClock.elapsedRealtime();
      rotation = image.rotation;
      size = image.buffer.remaining();
    }
  }

  SurfaceDecoder(
      MediaCodecInfo info,
      String mime,
      int fps,
      boolean lowLatency,
      Target target,
      VideoDecoder fallback,
      Runnable switchOutput,
      Runnable firstFrame) {
    this.info = info;
    this.mime = mime;
    this.fps = fps;
    this.lowLatency = lowLatency;
    this.target = target;
    this.fallback = fallback;
    this.switchOutput = switchOutput;
    this.firstFrame = firstFrame;
  }

  public VideoCodecStatus initDecode(VideoDecoder.Settings settings, Callback callback) {
    this.callback = callback;
    width = settings.width;
    height = settings.height;
    outputWidth = width;
    outputHeight = height;
    failed = false;
    try {
      Surface surface = target.get();
      if (surface == null || !surface.isValid())
        throw new IllegalStateException("No display surface");
      thread = new HandlerThread("BGFN-surface-codec", android.os.Process.THREAD_PRIORITY_DISPLAY);
      thread.start();
      Handler handler = new Handler(thread.getLooper());
      final int token;
      synchronized (lock) {
        token = ++generation;
        codec = MediaCodec.createByCodecName(info.getName());
      }
      MediaCodec current = codec;
      MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      if (lowLatency
          && Build.VERSION.SDK_INT >= 30
          && info.getCapabilitiesForType(mime)
              .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      current.setCallback(callbacks(token), handler);
      current.configure(format, surface, null, 0);
      current.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
      current.start();
      return VideoCodecStatus.OK;
    } catch (Exception error) {
      return useCompatible(settings);
    }
  }

  private VideoCodecStatus useCompatible(VideoDecoder.Settings settings) {
    releaseOwn();
    compatible = true;
    switchOutput.run();
    return fallback == null ? VideoCodecStatus.ERROR : fallback.initDecode(settings, callback);
  }

  public VideoCodecStatus decode(EncodedImage image, DecodeInfo details) {
    if (compatible)
      return fallback == null ? VideoCodecStatus.ERROR : fallback.decode(image, details);
    Surface display = target.get();
    if (failed || display == null || !display.isValid() || image.rotation != 0) {
      VideoCodecStatus status = useCompatible(new VideoDecoder.Settings(1, width, height));
      return status == VideoCodecStatus.OK ? fallback.decode(image, details) : status;
    }
    if (image.encodedWidth > 0
        && image.encodedHeight > 0
        && (image.encodedWidth != width || image.encodedHeight != height)) {
      if (image.frameType != EncodedImage.FrameType.VideoFrameKey)
        return VideoCodecStatus.REQUEST_SLI;
      releaseOwn();
      VideoCodecStatus result =
          initDecode(
              new VideoDecoder.Settings(1, image.encodedWidth, image.encodedHeight), callback);
      if (result != VideoCodecStatus.OK) return result;
      if (compatible)
        return fallback == null ? VideoCodecStatus.ERROR : fallback.decode(image, details);
    }
    synchronized (lock) {
      if (codec == null) return VideoCodecStatus.UNINITIALIZED;
      int size = image.buffer.remaining();
      if (size <= 0) return VideoCodecStatus.NO_OUTPUT;
      if (pending.size() >= 8 || inCodec.size() >= 32 || queuedBytes + size > 2 * 1024 * 1024)
        return VideoCodecStatus.REQUEST_SLI;
      // If the driver already has an input slot, copy directly into its owned buffer.
      // EncodedImage is valid for this call; queued frames still take an independent copy below.
      if (pending.isEmpty() && !inputs.isEmpty()) {
        Pending frame = new Pending(image, null);
        queuedBytes += size;
        try {
          queueInput(codec, inputs.removeFirst(), frame, image.buffer.duplicate());
          return VideoCodecStatus.OK;
        } catch (Exception error) {
          failed = true;
          return VideoCodecStatus.REQUEST_SLI;
        }
      }
      ByteBuffer copy = null;
      for (Iterator<ByteBuffer> iterator = pool.iterator(); iterator.hasNext(); ) {
        ByteBuffer candidate = iterator.next();
        if (candidate.capacity() >= size) {
          copy = candidate;
          iterator.remove();
          break;
        }
      }
      if (copy == null) copy = ByteBuffer.allocateDirect((size + 4095) & ~4095);
      copy.clear();
      copy.put(image.buffer.duplicate());
      copy.flip();
      pending.addLast(new Pending(image, copy));
      queuedBytes += size;
      try {
        flush(codec);
        return VideoCodecStatus.OK;
      } catch (Exception error) {
        failed = true;
        return VideoCodecStatus.REQUEST_SLI;
      }
    }
  }

  private void flush(MediaCodec current) {
    while (!pending.isEmpty() && !inputs.isEmpty()) {
      int index = inputs.removeFirst();
      Pending frame = pending.removeFirst();
      queueInput(current, index, frame, frame.data);
      ByteBuffer recycled = frame.data;
      frame.data = null;
      if (pool.size() < 4 && recycled.capacity() <= 512 * 1024) pool.addLast(recycled);
    }
  }

  private void queueInput(MediaCodec current, int index, Pending frame, ByteBuffer source) {
    ByteBuffer destination = current.getInputBuffer(index);
    if (destination == null || destination.capacity() < frame.size)
      throw new IllegalStateException("Codec input too small");
    destination.clear();
    destination.put(source);
    long pts = ++nextPts;
    inCodec.put(pts, frame);
    current.queueInputBuffer(index, 0, frame.size, pts, 0);
  }

  private MediaCodec.Callback callbacks(final int token) {
    return new MediaCodec.Callback() {
      public void onInputBufferAvailable(MediaCodec current, int index) {
        synchronized (lock) {
          if (current != codec || token != generation) return;
          inputs.addLast(index);
          try {
            flush(current);
          } catch (Exception error) {
            failed = true;
          }
        }
      }

      public void onOutputBufferAvailable(
          MediaCodec current, int index, MediaCodec.BufferInfo output) {
        final VideoFrame frame;
        final int elapsed;
        synchronized (lock) {
          if (current != codec || token != generation) return;
          Pending encoded = inCodec.get(output.presentationTimeUs);
          inCodec.remove(output.presentationTimeUs);
          if (encoded != null) queuedBytes -= encoded.size;
          if (encoded == null
              || failed
              || (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            try {
              current.releaseOutputBuffer(index, false);
            } catch (Exception ignored) {
            }
            return;
          }
          SurfaceFrame opaque =
              new SurfaceFrame(
                  outputWidth,
                  outputHeight,
                  render -> {
                    synchronized (lock) {
                      if (current != codec || token != generation) return;
                      Surface display = target.get();
                      try {
                        boolean present = render && !failed && display != null && display.isValid();
                        current.releaseOutputBuffer(index, present);
                        // Reveal the video after a real decoded frame is queued. Waiting for
                        // display
                        // timestamps can deadlock when the loading artwork occludes the
                        // SurfaceView.
                        if (present && !reportedFirst) {
                          reportedFirst = true;
                          firstFrame.run();
                        }
                      } catch (Exception error) {
                        failed = true;
                      }
                    }
                  });
          frame = new VideoFrame(opaque, encoded.rotation, encoded.timestamp);
          elapsed = (int) Math.min(1000, SystemClock.elapsedRealtime() - encoded.queued);
        }
        try {
          callback.onDecodedFrame(frame, elapsed, null);
        } catch (Exception error) {
          failed = true;
        } finally {
          frame.release();
        }
      }

      public void onOutputFormatChanged(MediaCodec current, MediaFormat format) {
        synchronized (lock) {
          if (current != codec || token != generation) return;
          try {
            outputWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            outputHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (format.containsKey("crop-right") && format.containsKey("crop-left"))
              outputWidth = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
            if (format.containsKey("crop-bottom") && format.containsKey("crop-top"))
              outputHeight = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
          } catch (Exception error) {
            failed = true;
          }
        }
      }

      public void onError(MediaCodec current, MediaCodec.CodecException error) {
        synchronized (lock) {
          if (current == codec && token == generation) failed = true;
        }
      }
    };
  }

  private void releaseOwn() {
    final MediaCodec previous;
    final HandlerThread previousThread;
    synchronized (lock) {
      generation++;
      previous = codec;
      codec = null;
      previousThread = thread;
      thread = null;
      pending.clear();
      inputs.clear();
      inCodec.clear();
      pool.clear();
      queuedBytes = 0;
    }
    if (previous != null) {
      try {
        previous.stop();
      } catch (Exception ignored) {
      }
      try {
        previous.release();
      } catch (Exception ignored) {
      }
    }
    if (previousThread != null) {
      previousThread.quitSafely();
      if (Thread.currentThread() != previousThread)
        try {
          previousThread.join(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
    }
  }

  public VideoCodecStatus release() {
    if (compatible) {
      compatible = false;
      return fallback == null ? VideoCodecStatus.OK : fallback.release();
    }
    releaseOwn();
    return VideoCodecStatus.OK;
  }

  public String getImplementationName() {
    return compatible
        ? (fallback == null ? "unavailable-hardware" : fallback.getImplementationName())
        : info.getName() + "/DirectSurface";
  }
}
