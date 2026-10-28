package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Regression checks for reducing work without losing controls or changing stream settings. */
public class StreamingWorkTest {
  private void field(Class<?> type, Object target, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private void invoke(Class<?> type, Object target, String name) {
    try {
      Method method = type.getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(target);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void idleCatalogWorkersRetireAndCanBeRecreatedOnReturn() throws Exception {
    MainActivity activity =
        mock(MainActivity.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Field f = MainActivity.class.getDeclaredField("jobs");
    f.setAccessible(true);
    ThreadPoolExecutor jobs = (ThreadPoolExecutor) f.get(activity);
    CountDownLatch retired = new CountDownLatch(1);
    AtomicInteger threads = new AtomicInteger();
    jobs.setThreadFactory(
        task ->
            new Thread(
                () -> {
                  try {
                    task.run();
                  } finally {
                    retired.countDown();
                  }
                },
                "catalog-test-" + threads.incrementAndGet()));
    jobs.setKeepAliveTime(20, TimeUnit.MILLISECONDS); // Speed up the real 30-second idle timeout.
    try {
      assertEquals("catalog", jobs.submit(() -> "catalog").get(5, TimeUnit.SECONDS));
      assertTrue(retired.await(5, TimeUnit.SECONDS));
      assertEquals(0, jobs.getPoolSize());
      assertEquals("resumed", jobs.submit(() -> "resumed").get(5, TimeUnit.SECONDS));
      assertEquals(2, threads.get());
      assertFalse(jobs.isShutdown());
    } finally {
      jobs.shutdownNow();
    }
  }

  @Test
  public void continuous250HzStickMotionNeedsOnly63PacketsPerSecond() {
    PadChanges pad = new PadChanges();
    PadChanges unpaced = new PadChanges();
    int packets = 0, before = 0;
    for (int ms = 0; ms < 1000; ms += 4) {
      if (unpaced.accept(0, 0, 0, ms + 1, 0, 0, 0, ms)) before++;
      if (pad.waitForAnalog(0, 0, 0, ms + 1, 0, 0, 0, ms) == 0
          && pad.accept(0, 0, 0, ms + 1, 0, 0, 0, ms)) packets++;
    }
    assertEquals(63, packets);
    assertEquals(250, before);
  }

  @Test
  public void pressReleaseAndTriggerEdgesBypassAnalogDelay() {
    PadChanges pad = new PadChanges();
    pad.accept(0, 0, 0, 100, 0, 0, 0, 0);
    assertEquals(15, pad.waitForAnalog(0, 0, 0, 200, 0, 0, 0, 1));
    assertEquals(0, pad.waitForAnalog(0x1000, 0, 0, 200, 0, 0, 0, 1));
    pad.accept(0x1000, 0, 0, 200, 0, 0, 0, 1);
    assertEquals(0, pad.waitForAnalog(0, 0, 0, 300, 0, 0, 0, 2));
    assertEquals(0, pad.waitForAnalog(0x1000, 1, 0, 300, 0, 0, 0, 2));
    pad.accept(0x1000, 1, 0, 300, 0, 0, 0, 2);
    assertEquals(0, pad.waitForAnalog(0x1000, 0, 0, 300, 0, 0, 0, 3));
    assertEquals(0, pad.waitForAnalog(0x1000, 1, 1, 300, 0, 0, 0, 3));
  }

  @Test
  public void neutralStateAndReconnectAreNeverDelayed() {
    PadChanges pad = new PadChanges();
    pad.accept(0, 0, 0, 100, -100, 0, 0, 0);
    assertEquals(0, pad.waitForAnalog(0, 0, 0, 0, 0, 0, 0, 1));
    pad.reset();
    assertEquals(0, pad.waitForAnalog(0, 0, 0, 200, 0, 0, 0, 1));
  }

  @Test
  public void firstStickMovementAlsoBypassesTheDelay() {
    PadChanges pad = new PadChanges();
    pad.accept(0, 0, 0, 0, 0, 0, 0, 0);
    assertEquals(0, pad.waitForAnalog(0, 0, 0, 100, 0, 0, 0, 1));
  }

  @Test
  public void idleKeepaliveDoesNotBecomeA60HzTimer() {
    PadChanges pad = new PadChanges();
    pad.accept(0, 0, 0, 0, 0, 0, 0, 0);
    assertEquals(0, pad.waitForAnalog(0, 0, 0, 0, 0, 0, 0, 1));
    assertFalse(pad.accept(0, 0, 0, 0, 0, 0, 0, 1));
    assertEquals(1000, pad.delay());
  }

  @Test
  public void activityKeepsQueuePollingFastAndSlowsItOnlyDuringPlayback() throws Exception {
    StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Handler handler = mock(Handler.class);
    TextView queue = mock(TextView.class);
    field(StreamActivity.class, activity, "ui", handler);
    field(StreamActivity.class, activity, "queue", queue);
    field(StreamActivity.class, activity, "l", new L(Locale.US));
    field(StreamActivity.class, activity, "started", true);
    SessionClient.Seat seat = new SessionClient.Seat();
    seat.status = 1;
    seat.queue = 12;
    Method update = StreamActivity.class.getDeclaredMethod("updateSeat", SessionClient.Seat.class);
    update.setAccessible(true);
    update.invoke(activity, seat);
    field(StreamActivity.class, activity, "polling", false);
    field(StreamActivity.class, activity, "playing", true);
    seat.status = 3;
    seat.queue = -1;
    update.invoke(activity, seat);
    verify(handler).postDelayed(any(Runnable.class), eq(3000L));
    verify(handler).postDelayed(any(Runnable.class), eq(10000L));
    verify(queue, times(1)).setText(any(CharSequence.class));
  }

  @Test
  public void activitySendsLatestStickStateAtDeadlineWithoutReplayingOldStates() throws Exception {
    StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Handler handler = mock(Handler.class);
    StreamEngine engine = mock(StreamEngine.class);
    FrameLayout toolbar = mock(FrameLayout.class);
    when(toolbar.getVisibility()).thenReturn(View.GONE);
    field(StreamActivity.class, activity, "ui", handler);
    field(StreamActivity.class, activity, "engine", engine);
    field(StreamActivity.class, activity, "toolbar", toolbar);
    field(StreamActivity.class, activity, "playing", true);
    field(StreamActivity.class, activity, "lx", 500);
    InputEncoder encoder = new InputEncoder();
    encoder.version = 2;
    field(StreamActivity.class, activity, "encoder", encoder);
    AtomicReference<Runnable> scheduled = new AtomicReference<>();
    when(handler.postDelayed(any(Runnable.class), anyLong()))
        .thenAnswer(
            call -> {
              scheduled.set(call.getArgument(0));
              return true;
            });
    AtomicLong now = new AtomicLong();
    try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
      clock.when(SystemClock::elapsedRealtime).thenAnswer(call -> now.get());
      clock.when(SystemClock::elapsedRealtimeNanos).thenAnswer(call -> now.get() * 1000000);
      invoke(StreamActivity.class, activity, "sendPad");
      now.set(4);
      field(StreamActivity.class, activity, "lx", 1000);
      invoke(StreamActivity.class, activity, "sendPad");
      now.set(8);
      field(StreamActivity.class, activity, "lx", 2000);
      invoke(StreamActivity.class, activity, "sendPad");
      verify(engine, times(1)).input(any(byte[].class));
      now.set(16);
      scheduled.get().run();
      ArgumentCaptor<byte[]> packets = ArgumentCaptor.forClass(byte[].class);
      verify(engine, times(2)).input(packets.capture());
      assertEquals(
          2000,
          ByteBuffer.wrap(packets.getAllValues().get(1))
              .order(ByteOrder.LITTLE_ENDIAN)
              .getShort(16));
      now.set(17);
      field(StreamActivity.class, activity, "lx", 0);
      invoke(StreamActivity.class, activity, "sendPad");
      verify(engine, times(3)).input(any(byte[].class));
    }
  }

  private final class Bridge {
    final WebEngine engine = mock(WebEngine.class, CALLS_REAL_METHODS);
    final Handler handler = mock(Handler.class);
    final WebView web = mock(WebView.class);
    final StreamEngine.Events events = mock(StreamEngine.Events.class);
    final List<Runnable> tasks = new ArrayList<>();

    Bridge() throws Exception {
      Activity activity = mock(Activity.class);
      doAnswer(
              call -> {
                ((Runnable) call.getArgument(0)).run();
                return null;
              })
          .when(activity)
          .runOnUiThread(any(Runnable.class));
      when(handler.post(any(Runnable.class)))
          .thenAnswer(
              call -> {
                tasks.add(call.getArgument(0));
                return true;
              });
      field(WebEngine.class, engine, "activity", activity);
      field(WebEngine.class, engine, "ui", handler);
      field(WebEngine.class, engine, "web", web);
      field(WebEngine.class, engine, "events", events);
      field(WebEngine.class, engine, "bridgeInputs", new ReliableInputs());
      field(WebEngine.class, engine, "inputPosted", new AtomicBoolean());
      field(
          WebEngine.class,
          engine,
          "flushInputs",
          (Runnable) () -> invoke(WebEngine.class, engine, "flushInputs"));
    }
  }

  @Test
  public void webBridgeBatchesKeyDownUpAndTextInOrderWithOneJavascriptCall() throws Exception {
    Bridge b = new Bridge();
    InputEncoder encoder = new InputEncoder();
    byte[] down = encoder.key(true, 13, 0, 0, 1),
        up = encoder.key(false, 13, 0, 0, 2),
        text = encoder.text("ação😀").get(0);
    b.engine.input(down);
    b.engine.input(up);
    b.engine.input(text);
    assertEquals(1, b.tasks.size());
    verifyNoInteractions(b.web);
    try (MockedStatic<Base64> base64 = mockStatic(Base64.class)) {
      base64
          .when(() -> Base64.encodeToString(any(byte[].class), eq(Base64.NO_WRAP)))
          .thenAnswer(call -> java.util.Base64.getEncoder().encodeToString(call.getArgument(0)));
      b.tasks.get(0).run();
    }
    String expected =
        "inputs([\""
            + java.util.Base64.getEncoder().encodeToString(down)
            + "\",\""
            + java.util.Base64.getEncoder().encodeToString(up)
            + "\",\""
            + java.util.Base64.getEncoder().encodeToString(text)
            + "\"])";
    verify(b.web).evaluateJavascript(eq(expected), isNull());
    verifyNoInteractions(b.events);
    b.engine.input(new byte[] {2});
    assertEquals(2, b.tasks.size());
  }

  @Test
  public void closingWebBridgeDropsPendingWorkAndRejectsLateInput() throws Exception {
    Bridge b = new Bridge();
    b.engine.input(new byte[] {1});
    b.engine.close();
    b.tasks.get(0).run();
    b.engine.input(new byte[] {2});
    assertEquals(1, b.tasks.size());
    verify(b.web, never()).evaluateJavascript(anyString(), any());
    verify(b.web).destroy();
  }

  @Test
  public void webBridgeKeepsPacketAndByteLimitsWhileWaitingForUi() throws Exception {
    Bridge packets = new Bridge();
    for (int i = 0; i < 129; i++) packets.engine.input(new byte[] {1});
    verify(packets.events, times(1)).error("Control input queue is full");
    assertEquals(1, packets.tasks.size());
    Bridge bytes = new Bridge();
    for (int i = 0; i < 65; i++) bytes.engine.input(new byte[1024]);
    verify(bytes.events, times(1)).error("Control input queue is full");
    assertEquals(1, bytes.tasks.size());
  }
}
