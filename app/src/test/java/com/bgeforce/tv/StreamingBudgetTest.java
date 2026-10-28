package com.bgeforce.tv;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class StreamingBudgetTest {
  private Settings settings(Map<String, Object> data, boolean constrained) {
    Object[] editor = {null};
    editor[0] =
        Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(),
            new Class[] {SharedPreferences.Editor.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.startsWith("put")) {
                data.put((String) args[0], args[1]);
                return editor[0];
              }
              if (name.equals("apply")) return null;
              if (name.equals("commit")) return true;
              throw new UnsupportedOperationException(name);
            });
    SharedPreferences prefs =
        (SharedPreferences)
            Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class[] {SharedPreferences.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("edit")) return editor[0];
                  if (method.getName().startsWith("get"))
                    return data.getOrDefault(args[0], args[1]);
                  throw new UnsupportedOperationException(method.getName());
                });
    return new Settings(prefs, constrained);
  }

  @Test
  public void aOneGbTvDefaultsTo720p60WithCompatibleHardwareOutput() {
    assertTrue(StreamBudget.constrained(false, 1024L * 1024 * 1024));
    Map<String, Object> saved = new HashMap<>();
    saved.put("fps", 60);
    saved.put("bitrate", 20);
    saved.put("codec", "AV1");
    Settings s = settings(saved, true);
    assertEquals(1280, s.width());
    assertEquals(720, s.height());
    assertEquals(60, s.fps());
    assertEquals(8000, s.kbps());
    assertEquals("H264", s.codec());
    assertFalse(s.directSurface());
    assertEquals("compatible", s.decoderPath());
    assertEquals(2, s.referenceFrames());
    assertEquals(60, saved.get("fps")); // selecting Custom can recover the saved preference
  }

  @Test
  public void aNormalDeviceRetainsItsManualStreamValues() {
    assertFalse(StreamBudget.constrained(false, 4L * 1024 * 1024 * 1024));
    Map<String, Object> saved = new HashMap<>();
    saved.put("performance", "custom");
    saved.put("width", 1920);
    saved.put("fps", 60);
    saved.put("codec", "H265");
    Settings s = settings(saved, false);
    assertEquals(1920, s.width());
    assertEquals(1080, s.height());
    assertEquals(60, s.fps());
    assertEquals("H265", s.codec());
  }

  @Test
  public void aOneGbDeviceKeepsTheSelectedCustomImageQualityAndFps() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("performance", "custom");
    saved.put("width", 1920);
    saved.put("fps", 60);
    saved.put("bitrate", 20);
    saved.put("codec", "H265");
    Settings s = settings(saved, true);
    assertEquals(1920, s.width());
    assertEquals(1080, s.height());
    assertEquals(60, s.fps());
    assertEquals(20000, s.kbps());
    assertEquals("H265", s.codec());
    String request =
        Sdp.nvst("", "", s.width(), s.height(), s.fps(), s.kbps(), false, s.referenceFrames());
    assertTrue(request.contains("video.maxFPS:60"));
    assertTrue(request.contains("video.clientViewportWd:1920"));
    assertTrue(request.contains("video.clientViewportHt:1080"));
  }

  @Test
  public void systemLowRamClassificationOverridesAnUnknownMemorySize() {
    assertTrue(StreamBudget.constrained(true, 0));
    assertFalse(StreamBudget.constrained(false, 0));
  }

  @Test
  public void ultraLightRemainsAnExplicitAlternative() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("performance", "ultra");
    Settings s = settings(saved, false);
    assertEquals(854, s.width());
    assertEquals(480, s.height());
    assertEquals(30, s.fps());
    assertEquals(3000, s.kbps());
  }

  @Test
  public void changingOneKnobKeepsOtherEffectiveValuesAndEntersCustomMode() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("fps", 60);
    saved.put("bitrate", 20);
    saved.put("width", 1920);
    Settings s = settings(saved, true);
    s.set("codec", "H265");
    assertEquals("custom", s.performanceMode());
    assertEquals("H265", s.codec());
    assertEquals(1280, s.width());
    assertEquals(60, s.fps());
    assertEquals(8000, s.kbps());
    s.set("fps", 60);
    assertEquals(60, s.fps());
  }

  @Test
  public void userCanDisableDirectSurfaceWithoutLosingOtherOptions() {
    Map<String, Object> saved = new HashMap<>();
    Settings s = settings(saved, true);
    s.set("directSurface", false);
    assertFalse(s.directSurface());
    assertEquals(60, s.fps());
    assertEquals("balanced", s.performanceMode());
  }

  @Test
  public void streamReferenceBudgetChangesWithoutChangingIceCredentials() {
    String nvst =
        Sdp.nvst("", "a=ice-ufrag:actual\r\na=ice-pwd:secret\r\n", 1280, 720, 30, 4000, false, 2);
    assertTrue(nvst.contains("maxNumReferenceFrames:2"));
    assertTrue(nvst.contains("video.maxFPS:30"));
    assertTrue(nvst.contains("icePassword:secret"));
  }

  @Test
  public void idleControllerDoesNotProduceSixtyPacketsPerSecond() {
    PadChanges pad = new PadChanges();
    int count = 0;
    for (int ms = 0; ms < 1000; ms += 16) if (pad.accept(0, 0, 0, 0, 0, 0, 0, ms)) count++;
    assertEquals(1, count);
    assertTrue(pad.accept(0, 0, 0, 0, 0, 0, 0, 1000));
  }

  @Test
  public void controllerChangesAndButtonReleaseAreSentImmediately() {
    PadChanges pad = new PadChanges();
    assertTrue(pad.accept(0, 0, 0, 0, 0, 0, 0, 0));
    assertTrue(pad.accept(0x1000, 0, 0, 0, 0, 0, 0, 1));
    assertFalse(pad.accept(0x1000, 0, 0, 0, 0, 0, 0, 2));
    assertTrue(pad.accept(0x1000, 0, 0, 0, 0, 0, 0, 101));
    assertTrue(pad.accept(0, 0, 0, 0, 0, 0, 0, 102));
    pad.reset();
    assertTrue(pad.accept(0, 0, 0, 0, 0, 0, 0, 103));
  }

  @Test
  public void textPacketMatchesUnicodeProtocolAndHasNoExtraV3Wrapper() {
    InputEncoder encoder = new InputEncoder();
    byte[] expected = {0x22, 23, 0, 0, 0, (byte) 0xc3, (byte) 0xa9, 0x40};
    assertArrayEquals(expected, encoder.text("é@").get(0));
    encoder.version = 2;
    assertArrayEquals(expected, encoder.text("é@").get(0));
    assertTrue(encoder.text("").isEmpty());
  }

  @Test
  public void textChunkingKeepsChineseAndEmojiIntact() {
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < 400; i++) value.append("中😀");
    List<byte[]> packets = new InputEncoder().text(value.toString());
    StringBuilder recovered = new StringBuilder();
    for (byte[] packet : packets) {
      assertTrue(packet.length <= 1021);
      String chunk = new String(packet, 5, packet.length - 5, StandardCharsets.UTF_8);
      assertFalse(chunk.contains("\ufffd"));
      recovered.append(chunk);
    }
    assertEquals(value.toString(), recovered.toString());
  }

  @Test
  public void reliableQueuePreservesDownUpAndTextOrder() {
    ReliableInputs queue = new ReliableInputs();
    InputEncoder encoder = new InputEncoder();
    byte[] down = encoder.key(true, 13, 0, 0, 1),
        up = encoder.key(false, 13, 0, 0, 2),
        text = encoder.text("senha").get(0);
    assertTrue(queue.offer(down));
    assertTrue(queue.offer(up));
    assertTrue(queue.offer(text));
    assertArrayEquals(down, queue.poll());
    assertArrayEquals(up, queue.poll());
    assertArrayEquals(text, queue.poll());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void aBlockedControlChannelCannotGrowMemoryIndefinitely() {
    ReliableInputs queue = new ReliableInputs();
    byte[] block = new byte[1024];
    for (int i = 0; i < 64; i++) assertTrue(queue.offer(block));
    assertFalse(queue.offer(block));
    for (int i = 0; i < 64; i++) assertSame(block, queue.poll());
    assertTrue(queue.isEmpty());
    assertTrue(queue.offer(block));
    queue.clear();
    assertTrue(queue.isEmpty());
  }

  @Test
  public void anOpaqueSurfaceFrameIsPresentedExactlyOnce() {
    List<Boolean> released = new ArrayList<>();
    SurfaceFrame buffer = new SurfaceFrame(1280, 720, released::add);
    assertNull(buffer.toI420());
    assertEquals(1280, buffer.getWidth());
    assertEquals(720, buffer.getHeight());
    buffer.retain();
    buffer.present();
    buffer.present();
    buffer.release();
    buffer.release();
    assertEquals(Collections.singletonList(true), released);
  }

  @Test
  public void aDroppedFrameReleasesTheCodecSlotWithoutPresenting() {
    List<Boolean> released = new ArrayList<>();
    SurfaceFrame buffer = new SurfaceFrame(1280, 720, released::add);
    buffer.release();
    buffer.present();
    assertEquals(Collections.singletonList(false), released);
  }

  @Test
  public void aFullSurfaceFrameCanBeRetainedWithoutAllocatingPixels() {
    List<Boolean> released = new ArrayList<>();
    SurfaceFrame buffer = new SurfaceFrame(1280, 720, released::add);
    assertSame(buffer, buffer.cropAndScale(0, 0, 1280, 720, 1280, 720));
    buffer.release();
    assertTrue(released.isEmpty());
    buffer.release();
    assertEquals(Collections.singletonList(false), released);
  }

  @Test
  public void oldAutomaticModeBecomes720p60WithoutErasingCustomValues() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("performance", "auto");
    saved.put("fps", 30);
    saved.put("width", 1920);
    saved.put("directSurface", true);
    Settings s = settings(saved, true);
    assertEquals("balanced", s.performanceMode());
    assertEquals(1280, s.width());
    assertEquals(720, s.height());
    assertEquals(60, s.fps());
    assertFalse(s.directSurface());
    s.set("performance", "custom");
    assertEquals(30, s.fps());
    assertEquals(1920, s.width());
  }

  @Test
  public void lightModeIsStillAnExplicit720p30Alternative() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("performance", "light");
    Settings s = settings(saved, true);
    assertEquals(1280, s.width());
    assertEquals(720, s.height());
    assertEquals(30, s.fps());
    assertEquals(4000, s.kbps());
  }

  @Test
  public void directOutputNeedsAnExplicitDecoderSelection() {
    Map<String, Object> saved = new HashMap<>();
    Settings s = settings(saved, true);
    s.set("decoderPath", "direct");
    assertTrue(s.directSurface());
    s.set("decoderPath", "fast");
    assertFalse(s.directSurface());
    assertEquals("fast", s.decoderPath());
    s.set("decoderPath", "invalid");
    assertEquals("compatible", s.decoderPath());
  }
}
