package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.webrtc.PeerConnection;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;

/** Real engine callback handling without creating a codec or loading a native library. */
public class NativeStatsTest {
  private final NativeEngine engine = mock(NativeEngine.class, CALLS_REAL_METHODS);
  private final PeerConnection peer = mock(PeerConnection.class);
  private final StreamEngine.Events events = mock(StreamEngine.Events.class);
  private final AtomicBoolean pending = new AtomicBoolean(true);
  private final AtomicInteger generation = new AtomicInteger(1);

  private void field(String name, Object value) throws Exception {
    Field f = NativeEngine.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(engine, value);
  }

  private void configure() throws Exception {
    field("peer", peer);
    field("events", events);
    field("metrics", new StreamMetrics());
    field("samplePending", pending);
    field("statsGeneration", generation);
    field("statisticsEnabled", true);
  }

  private void sample() throws Exception {
    Method m = NativeEngine.class.getDeclaredMethod("sampleStats", int.class);
    m.setAccessible(true);
    m.invoke(engine, 1);
  }

  private RTCStatsReport report() {
    Map<String, Object> members = new HashMap<>();
    members.put("kind", "video");
    members.put("bytesReceived", Double.POSITIVE_INFINITY);
    members.put("framesPerSecond", 40.0);
    return new RTCStatsReport(
        1000,
        Collections.singletonMap("video", new RTCStats(1000, "inbound-rtp", "video", members)));
  }

  @Test
  public void failedStatsRequestDoesNotEndTheMediaSession() throws Exception {
    configure();
    doThrow(new IllegalStateException("stats unavailable"))
        .when(peer)
        .getStats(any(RTCStatsCollectorCallback.class));
    sample();
    assertFalse(pending.get());
    verifyNoInteractions(events);
  }

  @Test
  public void statsCallbackCannotLeakAnExceptionThroughJni() throws Exception {
    configure();
    doThrow(new IllegalArgumentException("optional display failed")).when(events).stats(any());
    doAnswer(
            call -> {
              ((RTCStatsCollectorCallback) call.getArgument(0)).onStatsDelivered(report());
              return null;
            })
        .when(peer)
        .getStats(any(RTCStatsCollectorCallback.class));
    sample();
    assertFalse(pending.get());
    verify(events).stats(any());
    verify(events, never()).error(anyString());
  }

  @Test
  public void callbackFromDisabledGenerationIsDiscarded() throws Exception {
    configure();
    generation.incrementAndGet();
    doAnswer(
            call -> {
              ((RTCStatsCollectorCallback) call.getArgument(0)).onStatsDelivered(report());
              return null;
            })
        .when(peer)
        .getStats(any(RTCStatsCollectorCallback.class));
    sample();
    assertFalse(pending.get());
    verifyNoInteractions(events);
  }

  @Test
  public void closingBeforeStatsCollectionReleasesThePendingSlot() throws Exception {
    configure();
    field("peer", null);
    sample();
    assertFalse(pending.get());
    verifyNoInteractions(peer, events);
  }
}
