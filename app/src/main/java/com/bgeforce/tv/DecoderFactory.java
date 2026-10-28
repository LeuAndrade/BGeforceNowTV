package com.bgeforce.tv;

import android.media.*;
import android.os.Build;
import java.util.*;
import org.webrtc.*;

/** Never advertises a CPU decoder on a 1 GB television. */
public final class DecoderFactory implements VideoDecoderFactory {
  private final HardwareVideoDecoderFactory stock;
  private final EglBase.Context egl;
  private final Settings settings;
  private SurfaceDecoder.Target surface;
  private Runnable switchOutput, firstFrame;

  public DecoderFactory(EglBase.Context egl, Settings settings) {
    this.egl = egl;
    this.settings = settings;
    stock = new HardwareVideoDecoderFactory(egl, DecoderFactory::hardware);
  }

  public DecoderFactory(
      EglBase.Context egl,
      Settings settings,
      SurfaceDecoder.Target surface,
      Runnable switchOutput,
      Runnable firstFrame) {
    this(egl, settings);
    this.surface = surface;
    this.switchOutput = switchOutput;
    this.firstFrame = firstFrame;
  }

  static boolean hardware(MediaCodecInfo info) {
    if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated() && !info.isSoftwareOnly();
    String n = info.getName().toLowerCase(Locale.ROOT);
    return !n.startsWith("omx.google.")
        && !n.startsWith("c2.android.")
        && !n.startsWith("c2.google.")
        && !n.contains(".sw.")
        && !n.contains("ffmpeg");
  }

  public static String mime(String codec) {
    switch (codec) {
      case "H265":
        return "video/hevc";
      case "AV1":
        return "video/av01";
      default:
        return "video/avc";
    }
  }

  public static List<MediaCodecInfo> candidates(String codec, int width, int height, int fps) {
    List<MediaCodecInfo> out = new ArrayList<>();
    String mime = mime(codec);
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (info.isEncoder() || !hardware(info)) continue;
      try {
        MediaCodecInfo.CodecCapabilities c = info.getCapabilitiesForType(mime);
        MediaCodecInfo.VideoCapabilities v = c.getVideoCapabilities();
        if (v != null && v.areSizeAndRateSupported(width, height, fps)) out.add(info);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return out;
  }

  @android.annotation.SuppressLint("InlinedApi") // Caller guards the optional API-30 feature.
  static List<MediaCodecInfo> prioritizeLowLatency(
      List<MediaCodecInfo> candidates, String mime, boolean supported) {
    if (!supported || candidates.size() < 2) return candidates;
    List<MediaCodecInfo> preferred = new ArrayList<>(), ordinary = new ArrayList<>();
    for (MediaCodecInfo info : candidates) {
      boolean low = false;
      try {
        low =
            info.getCapabilitiesForType(mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
      } catch (RuntimeException ignored) {
      }
      (low ? preferred : ordinary).add(info);
    }
    preferred.addAll(ordinary);
    return preferred;
  }

  public static String report(Settings s) {
    StringBuilder b = new StringBuilder();
    for (String codec : new String[] {"H264", "H265", "AV1"}) {
      List<MediaCodecInfo> cc = candidates(codec, s.width(), s.height(), s.fps());
      b.append(codec).append(": ");
      if (cc.isEmpty()) b.append("—");
      else
        for (MediaCodecInfo c : cc) {
          b.append(c.getName());
          if (Build.VERSION.SDK_INT >= 30
              && c.getCapabilitiesForType(mime(codec))
                  .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
            b.append(" [low latency]");
          b.append(" ");
        }
      b.append('\n');
    }
    return b.toString();
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> result = new ArrayList<>();
    for (VideoCodecInfo info : stock.getSupportedCodecs())
      if (info.name.equalsIgnoreCase(settings.codec())
          && !candidates(info.name, settings.width(), settings.height(), settings.fps()).isEmpty())
        result.add(info);
    return result.toArray(new VideoCodecInfo[0]);
  }

  @Override
  public VideoDecoder createDecoder(VideoCodecInfo info) {
    if (!info.name.equalsIgnoreCase(settings.codec())) return null;
    List<MediaCodecInfo> choices =
        candidates(info.name, settings.width(), settings.height(), settings.fps());
    if (choices.isEmpty()) return null;
    if (!"compatible".equals(settings.decoderPath()) && settings.flag("lowLatency", true))
      choices = prioritizeLowLatency(choices, mime(info.name), Build.VERSION.SDK_INT >= 30);
    if (surface != null && settings.directSurface())
      return new SurfaceDecoder(
          choices.get(0),
          mime(info.name),
          settings.fps(),
          settings.flag("lowLatency", true),
          surface,
          stock.createDecoder(info),
          switchOutput,
          firstFrame);
    if ("compatible".equals(settings.decoderPath()) || !settings.flag("lowLatency", true))
      return stock.createDecoder(info);
    // A standards-based decoder path; no vendor keys or reflection into WebRTC internals.
    return new FastDecoder(
        choices.get(0), mime(info.name), egl, settings.fps(), stock.createDecoder(info));
  }
}
