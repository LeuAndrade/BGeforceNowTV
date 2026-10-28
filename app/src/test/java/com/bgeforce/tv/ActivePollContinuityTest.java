package com.bgeforce.tv;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.Handler;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;

public class ActivePollContinuityTest {
  private void set(Object o, String name, Object value) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  private StreamActivity activity(boolean playing, Exception failure) throws Exception {
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
    ExecutorService executor = mock(ExecutorService.class);
    doAnswer(
            c -> {
              ((Runnable) c.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    SessionClient client = mock(SessionClient.class);
    when(client.poll(any())).thenThrow(failure);
    set(a, "ui", ui);
    set(a, "network", executor);
    set(a, "sessions", client);
    set(a, "seat", new SessionClient.Seat());
    set(a, "playing", playing);
    doNothing().when(a).error(anyString());
    return a;
  }

  private void poll(StreamActivity a, int times) throws Exception {
    Field f = StreamActivity.class.getDeclaredField("pollSeat");
    f.setAccessible(true);
    for (int i = 0; i < times; i++) ((Runnable) f.get(a)).run();
  }

  @Test
  public void repeated500DoesNotTearDownAnActiveVideoTransport() throws Exception {
    StreamActivity a = activity(true, new Api.Failure(500, new JSONObject()));
    poll(a, 8);
    verify(a, never()).error(anyString());
  }

  @Test
  public void queueErrorsStillEndAfterTheBoundedBudget() throws Exception {
    StreamActivity a = activity(false, new Api.Failure(500, new JSONObject()));
    poll(a, 4);
    verify(a, times(1)).error(contains("500"));
  }

  @Test
  public void missingSessionIsReportedWithoutAPollingLoop() throws Exception {
    StreamActivity a = activity(true, new Api.Failure(404, new JSONObject()));
    poll(a, 1);
    verify(a).error(contains("404"));
  }
}
