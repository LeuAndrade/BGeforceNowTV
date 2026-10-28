package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.mockito.MockedStatic;

/** Input storms are bounded without delaying button edges or changing stream quality. */
public class ControllerDispatchTest {
  private final class Player implements AutoCloseable {
    final StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    final Handler handler = mock(Handler.class);
    final StreamEngine engine = mock(StreamEngine.class);
    final FrameLayout toolbar = mock(FrameLayout.class);
    final AtomicLong now = new AtomicLong();
    final TreeMap<Long, Runnable> pending = new TreeMap<>();
    final List<byte[]> packets = new ArrayList<>();
    final MockedStatic<SystemClock> clock = mockStatic(SystemClock.class);
    int posts, removes;

    Player() throws Exception {
      set("ui", handler);
      set("engine", engine);
      set("toolbar", toolbar);
      set("root", mock(FrameLayout.class));
      set("playing", true);
      set("physicalInput", true);
      InputEncoder encoder = new InputEncoder();
      encoder.version = 2;
      set("encoder", encoder);
      when(toolbar.getVisibility()).thenReturn(View.GONE);
      clock.when(SystemClock::elapsedRealtime).thenAnswer(c -> now.get());
      clock.when(SystemClock::elapsedRealtimeNanos).thenAnswer(c -> now.get() * 1000000);
      when(handler.postDelayed(any(Runnable.class), anyLong()))
          .thenAnswer(
              c -> {
                posts++;
                pending.put(now.get() + (long) c.getArgument(1), c.getArgument(0));
                return true;
              });
      doAnswer(
              c -> {
                removes++;
                Runnable r = c.getArgument(0);
                pending.values().removeIf(v -> v == r);
                return null;
              })
          .when(handler)
          .removeCallbacks(any(Runnable.class));
      doAnswer(
              c -> {
                packets.add(c.getArgument(0));
                return null;
              })
          .when(engine)
          .input(any(byte[].class));
    }

    void set(String name, Object value) throws Exception {
      Field f = StreamActivity.class.getDeclaredField(name);
      f.setAccessible(true);
      f.set(activity, value);
    }

    Object get(String name) throws Exception {
      Field f = StreamActivity.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(activity);
    }

    void call(String name) throws Exception {
      Method m = StreamActivity.class.getDeclaredMethod(name);
      m.setAccessible(true);
      m.invoke(activity);
    }

    void advance(long time) {
      while (!pending.isEmpty() && pending.firstKey() <= time) {
        Map.Entry<Long, Runnable> e = pending.pollFirstEntry();
        now.set(e.getKey());
        e.getValue().run();
      }
      now.set(time);
    }

    public void close() {
      clock.close();
    }
  }

  private KeyEvent key(int action, int code, int source) {
    KeyEvent e = mock(KeyEvent.class);
    when(e.getAction()).thenReturn(action);
    when(e.getKeyCode()).thenReturn(code);
    when(e.getSource()).thenReturn(source);
    return e;
  }

  private int buttons(byte[] p) {
    return ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getShort(12) & 65535;
  }

  @Test
  public void pressAndReleaseBypassPendingAnalogDeadline() throws Exception {
    try (Player p = new Player()) {
      p.set("lx", 100);
      p.call("sendPad");
      p.advance(4);
      p.set("lx", 200);
      p.call("sendPad");
      p.advance(5);
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.advance(6);
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      assertEquals(3, p.packets.size());
      assertEquals(0x1000, buttons(p.packets.get(1)));
      assertEquals(0, buttons(p.packets.get(2)));
    }
  }

  @Test
  public void heldButtonsKeepTheExistingHundredMillisecondKeepalive() throws Exception {
    try (Player p = new Player()) {
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.advance(99);
      assertEquals(1, p.packets.size());
      p.advance(100);
      assertEquals(2, p.packets.size());
      assertEquals(0x1000, buttons(p.packets.get(1)));
      assertEquals(1, p.pending.size());
    }
  }

  @Test
  public void duplicateAnalogSamplesDoNotTouchTheMainQueue() throws Exception {
    try (Player p = new Player()) {
      p.call("sendPad");
      int posts = p.posts, removes = p.removes;
      MotionEvent e = mock(MotionEvent.class);
      when(e.getSource()).thenReturn(InputDevice.SOURCE_JOYSTICK);
      for (int i = 0; i < 1000; i++) assertTrue(p.activity.onGenericMotionEvent(e));
      assertEquals(posts, p.posts);
      assertEquals(removes, p.removes);
      assertEquals(1, p.packets.size());
    }
  }

  @Test
  public void burstUsesOneDeadlineAndTheLatestAnalogPosition() throws Exception {
    try (Player p = new Player()) {
      p.set("lx", 100);
      p.call("sendPad");
      for (int i = 1; i < 16; i++) {
        p.advance(i);
        p.set("lx", 100 + i);
        p.call("sendPad");
      }
      assertEquals(1, p.packets.size());
      assertEquals(2, p.posts);
      assertEquals(1, p.removes);
      p.advance(16);
      assertEquals(2, p.packets.size());
      assertEquals(
          115, ByteBuffer.wrap(p.packets.get(1)).order(ByteOrder.LITTLE_ENDIAN).getShort(16));
    }
  }

  @Test
  public void neutralKeepaliveKeepsItsOriginalDeadlineAfterEarlyCallback() throws Exception {
    try (Player p = new Player()) {
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.advance(1);
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.advance(1000);
      assertEquals(2, p.packets.size());
      p.advance(1001);
      assertEquals(3, p.packets.size());
      assertEquals(0, buttons(p.packets.get(2)));
    }
  }

  @Test
  public void remoteDpadCenterProducesAnImmediateGamepadDownUp() throws Exception {
    try (Player p = new Player()) {
      KeyEvent down =
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, InputDevice.SOURCE_DPAD);
      assertTrue(p.activity.dispatchKeyEvent(down));
      assertTrue(
          p.activity.dispatchKeyEvent(
              key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, InputDevice.SOURCE_DPAD)));
      assertEquals(2, p.packets.size());
      assertEquals(0x1000, buttons(p.packets.get(0)));
      assertEquals(0, buttons(p.packets.get(1)));
      verify(down, never()).getDevice();
    }
  }

  @Test
  public void cachedControllerKeyboardSourceDoesNotCreateDuplicatePcArrowInput() throws Exception {
    try (Player p = new Player()) {
      @SuppressWarnings("unchecked")
      Set<Integer> ids = (Set<Integer>) p.get("padDevices");
      ids.add(42);
      KeyEvent down =
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_KEYBOARD);
      when(down.getDeviceId()).thenReturn(42);
      assertTrue(p.activity.dispatchKeyEvent(down));
      assertEquals(12, ByteBuffer.wrap(p.packets.get(0)).order(ByteOrder.LITTLE_ENDIAN).getInt());
      assertEquals(4, buttons(p.packets.get(0)));
      verify(down, never()).getDevice();
    }
  }

  @Test
  public void aRealKeyboardRetainsPcArrowKeys() throws Exception {
    try (Player p = new Player()) {
      KeyEvent down =
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_KEYBOARD);
      assertTrue(p.activity.dispatchKeyEvent(down));
      assertTrue(
          p.activity.dispatchKeyEvent(
              key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_KEYBOARD)));
      assertEquals(3, ByteBuffer.wrap(p.packets.get(0)).order(ByteOrder.LITTLE_ENDIAN).getInt());
      assertEquals(4, ByteBuffer.wrap(p.packets.get(1)).order(ByteOrder.LITTLE_ENDIAN).getInt());
      assertTrue(p.pending.isEmpty());
      verify(down, never()).getDevice();
    }
  }

  @Test
  public void suspendedControlsCancelTheSinglePendingTimerAndCanResume() throws Exception {
    try (Player p = new Player()) {
      p.call("sendPad");
      p.call("suspendControls");
      assertTrue(p.pending.isEmpty());
      int count = p.packets.size();
      p.advance(3000);
      assertEquals(count, p.packets.size());
      p.call("resumeControls");
      assertEquals(1, p.pending.size());
      p.advance(4000);
      assertTrue(p.packets.size() > count);
    }
  }

  @Test
  public void simultaneousButtonsPreserveEveryPressAndReleaseEdge() throws Exception {
    try (Player p = new Player()) {
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, InputDevice.SOURCE_GAMEPAD));
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, InputDevice.SOURCE_GAMEPAD));
      assertEquals(4, p.packets.size());
      assertEquals(0x1000, buttons(p.packets.get(0)));
      assertEquals(0x3000, buttons(p.packets.get(1)));
      assertEquals(0x2000, buttons(p.packets.get(2)));
      assertEquals(0, buttons(p.packets.get(3)));
    }
  }

  @Test
  public void firstObservedRepeatAfterAnOverlayRestoresTheHeldButton() throws Exception {
    try (Player p = new Player()) {
      p.activity.dispatchKeyEvent(
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD));
      p.call("suspendControls");
      p.call("resumeControls");
      KeyEvent repeated =
          key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD);
      when(repeated.getRepeatCount()).thenReturn(7);
      assertTrue(p.activity.dispatchKeyEvent(repeated));
      assertEquals(0x1000, buttons(p.packets.get(p.packets.size() - 1)));
    }
  }

  @Test
  public void aHybridControllersAlphabeticKeyboardStillTypesInVirtualMouseMode() throws Exception {
    try (Player p = new Player()) {
      @SuppressWarnings("unchecked")
      Set<Integer> ids = (Set<Integer>) p.get("padDevices");
      ids.add(42);
      p.set("virtualMouse", true);
      KeyEvent down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, InputDevice.SOURCE_KEYBOARD);
      when(down.getDeviceId()).thenReturn(42);
      InputDevice device = mock(InputDevice.class);
      when(device.getKeyboardType()).thenReturn(InputDevice.KEYBOARD_TYPE_ALPHABETIC);
      when(down.getDevice()).thenReturn(device);
      assertTrue(p.activity.dispatchKeyEvent(down));
      assertEquals(1, p.packets.size());
      assertEquals(3, ByteBuffer.wrap(p.packets.get(0)).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
  }
}
