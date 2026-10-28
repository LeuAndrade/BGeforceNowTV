package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.Handler;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.mockito.MockedStatic;

/** Exercises the actual timeout/recovery path, with no media/JNI or cloud mutations. */
public class StartupWatchdogTest {
  private void set(Object o, String name, Object value) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  private Object get(Object o, String name) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(o);
  }

  private StreamActivity setup(SessionClient client, StreamEngine engine) throws Exception {
    StreamActivity a =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Handler ui = mock(Handler.class);
    when(ui.post(any(Runnable.class)))
        .thenAnswer(
            c -> {
              ((Runnable) c.getArgument(0)).run();
              return true;
            });
    ExecutorService work = mock(ExecutorService.class);
    doAnswer(
            c -> {
              ((Runnable) c.getArgument(0)).run();
              return null;
            })
        .when(work)
        .execute(any(Runnable.class));
    set(a, "ui", ui);
    set(a, "network", work);
    set(a, "root", mock(FrameLayout.class));
    set(a, "loading", mock(FrameLayout.class));
    set(a, "toolbar", mock(FrameLayout.class));
    set(a, "hud", mock(TextView.class));
    set(a, "queue", mock(TextView.class));
    set(a, "l", new L(Locale.US));
    set(a, "settings", SessionFlowTest.settings(Collections.singletonMap("performance", "fullhd")));
    set(a, "sessions", client);
    set(a, "engine", engine);
    set(a, "started", true);
    set(a, "offerAt", 0L);
    SessionClient.Seat seat = new SessionClient.Seat();
    seat.id = "existing-session";
    seat.status = 3;
    set(a, "seat", seat);
    doNothing().when(a).error(anyString());
    return a;
  }

  @Test
  public void firstTimeoutRefreshesOnlyTheSameSessionWithoutReducingQuality() throws Exception {
    SessionClient client = mock(SessionClient.class);
    StreamEngine engine = mock(StreamEngine.class);
    SessionClient.Seat queued = new SessionClient.Seat();
    queued.id = "existing-session";
    queued.status = 1;
    when(client.poll(any())).thenReturn(queued);
    StreamActivity a = setup(client, engine);
    try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
      clock.when(SystemClock::elapsedRealtime).thenReturn(46001L);
      ((Runnable) get(a, "watchdog")).run();
    }
    verify(client, times(1)).poll(any());
    verify(client, never()).create(any(), any());
    verify(client, never()).resume(anyString(), anyString(), anyString());
    verify(client, never()).stop(any());
    verify(engine).close();
    assertSame(queued, get(a, "seat"));
    Settings settings = (Settings) get(a, "settings");
    assertEquals(1920, settings.width());
    assertEquals(60, settings.fps());
    verify(a, never()).error(anyString());
  }

  @Test
  public void secondTimeoutReportsTransportStateInsteadOfLoopingTheRecovery() throws Exception {
    SessionClient client = mock(SessionClient.class);
    SessionClient.Seat queued = new SessionClient.Seat();
    queued.id = "existing-session";
    queued.status = 1;
    when(client.poll(any())).thenReturn(queued);
    StreamActivity a = setup(client, mock(StreamEngine.class));
    try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
      clock.when(SystemClock::elapsedRealtime).thenReturn(46001L);
      ((Runnable) get(a, "watchdog")).run();
      StreamEngine next = mock(StreamEngine.class);
      when(next.startupState()).thenReturn("SDP=recebido · ICE=CHECKING");
      set(a, "engine", next);
      set(a, "started", true);
      ((Runnable) get(a, "watchdog")).run();
    }
    verify(client, times(1)).poll(any());
    verify(a).error(contains("ICE=CHECKING"));
  }

  @Test
  public void anActivePictureIsNeverRestartedByTheStartupWatchdog() throws Exception {
    SessionClient client = mock(SessionClient.class);
    StreamEngine engine = mock(StreamEngine.class);
    StreamActivity a = setup(client, engine);
    set(a, "playing", true);
    try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
      clock.when(SystemClock::elapsedRealtime).thenReturn(90000L);
      ((Runnable) get(a, "watchdog")).run();
    }
    verifyNoInteractions(client);
    verify(engine, never()).close();
    verify(a, never()).error(anyString());
  }
}
