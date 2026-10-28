package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class FullHdProfileTest {
  @Test
  public void explicitFullHdRemains1080p60EvenOnALowRamTv() {
    Settings s =
        new Settings(
            SessionFlowTest.settings(Collections.singletonMap("performance", "fullhd")).prefs,
            true);
    assertEquals("fullhd", s.performanceMode());
    assertEquals(1920, s.width());
    assertEquals(1080, s.height());
    assertEquals(60, s.fps());
    assertEquals(15000, s.kbps());
    assertEquals("H264", s.codec());
    assertEquals("direct", s.decoderPath());
    assertEquals(2, s.referenceFrames());
  }

  @Test
  public void newInstallStillUsesTheWorking720p60Profile() {
    Settings s = new Settings(SessionFlowTest.settings(Collections.emptyMap()).prefs, true);
    assertEquals(1280, s.width());
    assertEquals(60, s.fps());
    assertEquals("compatible", s.decoderPath());
  }

  @Test
  public void fullHdRespectsAnExplicitlySavedDecoderChoice() {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put("performance", "fullhd");
    prefs.put("decoderPath", "compatible");
    Settings s = SessionFlowTest.settings(prefs);
    assertEquals(1920, s.width());
    assertEquals("compatible", s.decoderPath());
  }
}
