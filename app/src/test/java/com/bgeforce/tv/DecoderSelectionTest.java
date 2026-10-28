package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.media.MediaCodecInfo;
import java.util.*;
import org.junit.Test;

public class DecoderSelectionTest {
  @Test
  public void advancedOutputPrefersReportedLowLatencyHardwareWithoutReorderingTies() {
    MediaCodecInfo normal = mock(MediaCodecInfo.class),
        fast = mock(MediaCodecInfo.class),
        fast2 = mock(MediaCodecInfo.class);
    MediaCodecInfo.CodecCapabilities a = mock(MediaCodecInfo.CodecCapabilities.class),
        b = mock(MediaCodecInfo.CodecCapabilities.class);
    when(normal.getCapabilitiesForType("video/avc")).thenReturn(a);
    when(fast.getCapabilitiesForType("video/avc")).thenReturn(b);
    when(fast2.getCapabilitiesForType("video/avc")).thenReturn(b);
    when(b.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
        .thenReturn(true);
    assertEquals(
        Arrays.asList(fast, fast2, normal),
        DecoderFactory.prioritizeLowLatency(Arrays.asList(normal, fast, fast2), "video/avc", true));
  }

  @Test
  public void oldAndroidNeverQueriesTheUnsupportedFeature() {
    MediaCodecInfo codec = mock(MediaCodecInfo.class);
    List<MediaCodecInfo> list = Arrays.asList(codec, codec);
    assertSame(list, DecoderFactory.prioritizeLowLatency(list, "video/avc", false));
    verifyNoInteractions(codec);
  }
}
