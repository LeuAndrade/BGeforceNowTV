package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.Handler;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.reflect.*;
import org.junit.Test;

/** Regression: repeated hardware key-down events must not re-arm an unchanged gamepad timer. */
public class ControllerOverheadTest {
  private void field(Object target, String name, Object value) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  public void tenThousandRepeatedButtonEventsKeepOnePendingControllerCallback() throws Exception {
    StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    Handler ui = mock(Handler.class);
    when(ui.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
    StreamEngine engine = mock(StreamEngine.class);
    FrameLayout toolbar = mock(FrameLayout.class);
    when(toolbar.getVisibility()).thenReturn(View.GONE);
    field(activity, "ui", ui);
    field(activity, "engine", engine);
    field(activity, "toolbar", toolbar);
    field(activity, "playing", true);
    field(activity, "physicalInput", true);
    KeyEvent press = mock(KeyEvent.class);
    when(press.getKeyCode()).thenReturn(KeyEvent.KEYCODE_BUTTON_A);
    when(press.getSource()).thenReturn(InputDevice.SOURCE_GAMEPAD);
    when(press.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    for (int i = 0; i < 10000; i++) assertTrue(activity.dispatchKeyEvent(press));
    long posts =
        mockingDetails(ui).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("postDelayed"))
            .count();
    long queries =
        mockingDetails(press).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("getDevice"))
            .count();
    System.out.println(
        "10000 repeated button events: controller_posts=" + posts + " device_queries=" + queries);
    verify(engine, times(1)).input(any(byte[].class));
    verify(ui, times(1)).postDelayed(any(Runnable.class), anyLong());
    verify(press, never()).getDevice();
  }
}
