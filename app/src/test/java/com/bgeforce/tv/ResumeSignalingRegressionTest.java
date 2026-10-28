package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** Reproduces the first RESUME handshake through the production activity launch path. */
public class ResumeSignalingRegressionTest {
  private void set(Class<?> type, Object target, String name, Object value) throws Exception {
    Field f = type.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  public void aClaimedSessionStillStartsWithAFreshWebRtcHandshake() throws Exception {
    StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Handler ui = mock(Handler.class);
    SharedPreferences prefs = mock(SharedPreferences.class);
    SharedPreferences.Editor edit = mock(SharedPreferences.Editor.class);
    when(prefs.edit()).thenReturn(edit);
    when(edit.putString(anyString(), anyString())).thenReturn(edit);
    when(prefs.getString(anyString(), anyString())).thenAnswer(c -> c.getArgument(1));
    Settings settings = new Settings(prefs);
    Api api = mock(Api.class);
    set(Api.class, api, "settings", settings);
    when(api.restore()).thenReturn(true);
    when(api.userId()).thenReturn("fixture-user");
    SessionClient sessions = mock(SessionClient.class);
    SessionClient.Seat claimed = new SessionClient.Seat();
    claimed.id = "existing";
    claimed.resumed = true;
    claimed.status = 3;
    claimed.signaling = "wss://game.nvidiagrid.net/nvst/";
    Intent intent = mock(Intent.class);
    when(activity.getIntent()).thenReturn(intent);
    when(intent.getStringExtra("variant")).thenReturn("12345");
    when(intent.getStringExtra("resumeId")).thenReturn("existing");
    when(intent.getStringExtra("resumeBase")).thenReturn("https://game.nvidiagrid.net/");
    when(sessions.resume(anyString(), eq("existing"), eq("12345"))).thenReturn(claimed);
    ExecutorService worker = mock(ExecutorService.class);
    doAnswer(
            c -> {
              ((Runnable) c.getArgument(0)).run();
              return null;
            })
        .when(worker)
        .execute(any(Runnable.class));
    set(StreamActivity.class, activity, "ui", ui);
    set(StreamActivity.class, activity, "api", api);
    set(StreamActivity.class, activity, "settings", settings);
    set(StreamActivity.class, activity, "sessions", sessions);
    set(StreamActivity.class, activity, "game", SessionFlowTest.game());
    set(StreamActivity.class, activity, "network", worker);
    List<Boolean> modes = new ArrayList<>();
    try (MockedStatic<CrashTrace> trace = mockStatic(CrashTrace.class);
        MockedConstruction<Signaling> signals =
            mockConstruction(
                Signaling.class, (s, c) -> modes.add((Boolean) c.arguments().get(2)))) {
      Method launch = StreamActivity.class.getDeclaredMethod("launch", boolean.class);
      launch.setAccessible(true);
      launch.invoke(activity, false);
      activity.ready();
      assertEquals(Collections.singletonList(false), modes);
      verify(sessions, times(1)).resume(anyString(), anyString(), anyString());
      verify(sessions, never()).create(any(), any());
    }
  }
}
