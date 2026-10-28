package com.bgeforce.tv;

import java.util.Map;
import org.json.JSONObject;

/** Optional two-second samples. No frame copying, decoding or work on the render thread. */
final class StreamMetrics {
  private long previousTime = -1;
  private String previousId = "";
  private double previousBytes = -1, previousFrames = -1, previousDecode = -1;

  synchronized void reset() {
    previousTime = -1;
    previousId = "";
    previousBytes = previousFrames = previousDecode = -1;
  }

  synchronized JSONObject sample(
      String id, Map<String, Object> video, Map<String, Object> pair, String codec, long now) {
    double bytes = number(video, "bytesReceived"),
        frames = number(video, "framesDecoded"),
        decode = number(video, "totalDecodeTime");
    boolean same = previousTime >= 0 && previousId.equals(id) && now > previousTime;
    double elapsed = same ? (double) now - previousTime : -1;
    double byteDelta = same ? delta(bytes, previousBytes) : -1;
    double frameDelta = same ? delta(frames, previousFrames) : -1;
    double decodeDelta = same ? delta(decode, previousDecode) : -1;
    double mbps = byteDelta < 0 ? -1 : finite(byteDelta * .008 / elapsed);
    double fps = number(video, "framesPerSecond");
    if (fps < 0 && frameDelta >= 0) fps = finite(frameDelta * 1000 / elapsed);
    double decodeMs =
        frameDelta > 0 && decodeDelta >= 0 ? finite(decodeDelta * 1000 / frameDelta) : -1;
    // Rebase after missing samples, counter resets, a new SSRC or a non-advancing clock.
    previousTime = now;
    previousId = id;
    previousBytes = bytes;
    previousFrames = frames;
    previousDecode = decode;
    double rtt = number(pair, "currentRoundTripTime");
    return Json.o(
        "fps",
        fps,
        "width",
        Math.max(0, number(video, "frameWidth")),
        "height",
        Math.max(0, number(video, "frameHeight")),
        "mbps",
        mbps,
        "decodeMs",
        decodeMs,
        "dropped",
        Math.max(0, number(video, "framesDropped")),
        "codec",
        codec,
        "decoder",
        text(video.get("decoderImplementation")),
        "ping",
        rtt < 0 ? -1 : finite(rtt * 1000));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static double delta(double value, double previous) {
    return value < 0 || previous < 0 || value < previous ? -1 : finite(value - previous);
  }

  private static double number(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value instanceof Number ? finite(((Number) value).doubleValue()) : -1;
  }

  private static double finite(double value) {
    return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? -1 : value;
  }
}
