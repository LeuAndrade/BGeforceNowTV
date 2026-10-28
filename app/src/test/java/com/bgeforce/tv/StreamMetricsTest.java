package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class StreamMetricsTest {
  private final StreamMetrics metrics = new StreamMetrics();

  private Map<String, Object> video(double bytes, double frames, double decode) {
    Map<String, Object> m = new HashMap<>();
    m.put("bytesReceived", bytes);
    m.put("framesDecoded", frames);
    m.put("totalDecodeTime", decode);
    m.put("frameWidth", 1280);
    m.put("frameHeight", 720);
    return m;
  }

  private JSONObject sample(Map<String, Object> video, long time) {
    return metrics.sample("ssrc", video, null, "video/H264", time);
  }

  @Test
  public void firstSampleDoesNotInventBitrateOrRecentDecodeTime() {
    JSONObject out = sample(video(1000, 60, .24), 0);
    assertEquals(-1, out.optDouble("mbps"), 0);
    assertEquals(-1, out.optDouble("decodeMs"), 0);
    assertEquals(-1, out.optDouble("ping"), 0);
    assertEquals(1280, out.optInt("width"));
  }

  @Test
  public void decodeSpikeUsesRecentIntervalInsteadOfLifetimeMean() {
    sample(video(1_000_000, 1000, 4), 1000);
    JSONObject out = sample(video(3_000_000, 1120, 4.96), 3000);
    assertEquals(8, out.optDouble("mbps"), .0001);
    assertEquals(60, out.optDouble("fps"), .0001);
    assertEquals(8, out.optDouble("decodeMs"), .0001);
  }

  @Test
  public void repeatedTimestampNeverDividesByZero() {
    sample(video(1000, 60, .24), 1000);
    JSONObject out = sample(video(2000, 120, .48), 1000);
    assertEquals(-1, out.optDouble("mbps"), 0);
    assertEquals(-1, out.optDouble("decodeMs"), 0);
    assertFalse(out.toString().contains("Infinity"));
  }

  @Test
  public void backwardsTimeRebasesBeforeNextValidSample() {
    sample(video(1000, 60, .24), 2000);
    assertEquals(-1, sample(video(2000, 120, .48), 1000).optDouble("mbps"), 0);
    assertEquals(60, sample(video(3000, 180, .72), 2000).optDouble("fps"), 0);
  }

  @Test
  public void counterResetNeverReportsNegativeThroughputOrFalseDecodeSpeed() {
    sample(video(1_000_000, 1000, 4), 1000);
    JSONObject out = sample(video(200, 2, .01), 3000);
    assertEquals(-1, out.optDouble("mbps"), 0);
    assertEquals(-1, out.optDouble("fps"), 0);
    assertEquals(-1, out.optDouble("decodeMs"), 0);
    assertEquals(60, sample(video(400, 122, .61), 5000).optDouble("fps"), 0);
  }

  @Test
  public void changedSsrcCannotUsePreviousTrackCounters() {
    sample(video(1000, 10, .04), 1000);
    JSONObject out = metrics.sample("new-ssrc", video(8_000_000, 9000, 30), null, "", 3000);
    assertEquals(-1, out.optDouble("mbps"), 0);
    assertEquals(-1, out.optDouble("decodeMs"), 0);
  }

  @Test
  public void nonfiniteAndMissingFieldsRemainJsonSafe() {
    Map<String, Object> m = video(Double.POSITIVE_INFINITY, Double.NaN, -1);
    m.put("framesPerSecond", Double.NEGATIVE_INFINITY);
    m.put("frameWidth", Double.NaN);
    Map<String, Object> pair = new HashMap<>();
    pair.put("currentRoundTripTime", Double.NaN);
    JSONObject out = metrics.sample("ssrc", m, pair, "", 3000);
    assertEquals(-1, out.optDouble("fps"), 0);
    assertEquals(-1, out.optDouble("ping"), 0);
    assertEquals(0, out.optInt("width"));
    assertFalse(out.toString().contains("NaN"));
    assertFalse(out.toString().contains("Infinity"));
  }

  @Test
  public void idleDecoderIsZeroFpsWithoutInventingZeroDecodeLatency() {
    sample(video(1000, 60, .24), 1000);
    JSONObject out = sample(video(1000, 60, .24), 3000);
    assertEquals(0, out.optDouble("fps"), 0);
    assertEquals(0, out.optDouble("mbps"), 0);
    assertEquals(-1, out.optDouble("decodeMs"), 0);
  }

  @Test
  public void webRtcReportedFpsIsKeptWhenAvailable() {
    Map<String, Object> m = video(1000, 60, .24);
    m.put("framesPerSecond", 39.5);
    assertEquals(39.5, sample(m, 1000).optDouble("fps"), 0);
  }

  @Test
  public void togglingStatisticsStartsANewInterval() {
    sample(video(1000, 60, .24), 1000);
    metrics.reset();
    assertEquals(-1, sample(video(9_000_000, 9000, 90), 3000).optDouble("mbps"), 0);
  }

  @Test
  public void hugeOrWronglyTypedValuesCannotEscapeAsInvalidJson() {
    Map<String, Object> m = video(Double.MAX_VALUE, 100, Double.MAX_VALUE);
    m.put("framesPerSecond", "60");
    Map<String, Object> pair = new HashMap<>();
    pair.put("currentRoundTripTime", Double.MAX_VALUE);
    JSONObject out = metrics.sample("ssrc", m, pair, "", 1000);
    assertEquals(-1, out.optDouble("ping"), 0);
    assertEquals(-1, out.optDouble("fps"), 0);
    assertNotNull(out.toString());
  }
}
