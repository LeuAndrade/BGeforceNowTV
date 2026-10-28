package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.os.Handler;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PointerControlsTest {
  @Test
  public void deviceRemovalStopsVirtualMovementAndReleasesGamepadAndKeys() throws Exception {
    Player p = new Player();
    field(p.activity, "virtualMouse", true);
    field(p.activity, "virtualDirections", 8);
    field(p.activity, "keyButtons", 0x1000);
    try (org.mockito.MockedStatic<InputDevice> devices = mockStatic(InputDevice.class)) {
      devices.when(InputDevice::getDeviceIds).thenReturn(new int[0]);
      p.activity.onInputDeviceRemoved(1);
    }
    assertEquals(false, get(p.activity, "virtualMouse"));
    assertEquals(0, get(p.activity, "virtualDirections"));
    assertEquals(0, get(p.activity, "keyButtons"));
    assertEquals(12, le(p.packets().get(0)).getInt(0));
  }

  private ByteBuffer le(byte[] p) {
    return ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
  }

  private ByteBuffer be(byte[] p) {
    return ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN);
  }

  private InputEncoder encoder() {
    InputEncoder e = new InputEncoder();
    e.version = 2;
    return e;
  }

  @Test
  public void absoluteCoordinatesMatchTheGfnWireFormatAndClampToVideoExtent() {
    byte[] p = encoder().mouseAbsolute(640, 360, 1280, 720, 1234);
    assertEquals(26, p.length);
    assertEquals(5, le(p).getInt(0));
    assertEquals(640, be(p).getShort(4));
    assertEquals(360, be(p).getShort(6));
    assertEquals(1280, be(p).getShort(10));
    assertEquals(720, be(p).getShort(12));
    assertEquals(1234, be(p).getLong(18));
    byte[] clamped = encoder().mouseAbsolute(-30, 9999, 1280, 720, 0);
    assertEquals(0, be(clamped).getShort(4));
    assertEquals(719, be(clamped).getShort(6));
  }

  @Test
  public void absolutePointerHasTheSameV3BatchFramingAsMouseMotion() {
    byte[] p = new InputEncoder().mouseAbsolute(1, 2, 1280, 720, 500);
    assertEquals(38, p.length);
    assertEquals(0x23, p[0]);
    assertEquals(0x21, p[9]);
    assertEquals(26, be(p).getShort(10));
    assertEquals(5, le(p).getInt(12));
    assertEquals(500, be(p).getLong(1));
  }

  @Test
  public void touchGeometryExcludesBlackBarsAndKeepsTheGameCoordinateSystem() {
    VideoViewport v = new VideoViewport(1280, 960, 1280, 720);
    assertEquals(120, v.top, .001);
    assertFalse(v.contains(640, 100));
    assertTrue(v.contains(640, 480));
    assertEquals(640, v.x(640));
    assertEquals(360, v.y(480));
    assertEquals(0, v.y(100));
    assertEquals(719, v.y(999));
    VideoViewport wide = new VideoViewport(1920, 1080, 2560, 1080);
    assertTrue(wide.top > 0);
    assertEquals(1280, wide.x(960));
    assertEquals(540, wide.y(540));
  }

  @Test
  public void aThousandMouseSamplesRetainTheirDistanceWithOnly63Packets() {
    PointerMotion motion = new PointerMotion();
    int packets = 0, x = 0, y = 0;
    for (int ms = 0; ms < 1000; ms++) {
      motion.relative(1, -1);
      if (ms % 16 == 15) {
        byte[] p = motion.drain(encoder(), ms);
        packets++;
        x += be(p).getShort(4);
        y += be(p).getShort(6);
      }
    }
    byte[] last = motion.drain(encoder(), 1000);
    packets++;
    x += be(last).getShort(4);
    y += be(last).getShort(6);
    assertEquals(63, packets);
    assertEquals(1000, x);
    assertEquals(-1000, y);
    assertFalse(motion.pending());
  }

  @Test
  public void fractionalMotionAndLargeDeltasAreNotLostOrWrapped() {
    PointerMotion m = new PointerMotion();
    m.relative(.25, -.25);
    assertNull(m.drain(encoder(), 0));
    for (int i = 0; i < 3; i++) m.relative(.25, -.25);
    byte[] p = m.drain(encoder(), 1);
    assertEquals(1, be(p).getShort(4));
    assertEquals(-1, be(p).getShort(6));
    m.relative(50000, -50000);
    byte[] a = m.drain(encoder(), 2), b = m.drain(encoder(), 3);
    assertEquals(50000, be(a).getShort(4) + be(b).getShort(4));
    assertEquals(-50000, be(a).getShort(6) + be(b).getShort(6));
    assertFalse(m.pending());
  }

  @Test
  public void absoluteMotionUsesTheLatestPositionAndClearDiscardsPendingWork() {
    PointerMotion m = new PointerMotion();
    m.absolute(10, 20, 1280, 720);
    m.absolute(300, 400, 1280, 720);
    byte[] p = m.drain(encoder(), 1);
    assertEquals(300, be(p).getShort(4));
    assertEquals(400, be(p).getShort(6));
    m.relative(20, 30);
    m.clear();
    assertFalse(m.pending());
    assertNull(m.drain(encoder(), 2));
  }

  private void field(Object target, String name, Object value) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private Object get(Object target, String name) throws Exception {
    Field f = StreamActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private Object call(Object target, String name, Class<?> type, Object arg) throws Exception {
    Method m = StreamActivity.class.getDeclaredMethod(name, type);
    m.setAccessible(true);
    return m.invoke(target, arg);
  }

  private void call(Object target, String name) throws Exception {
    Method m = StreamActivity.class.getDeclaredMethod(name);
    m.setAccessible(true);
    m.invoke(target);
  }

  private final class Player {
    final StreamActivity activity =
        mock(
            StreamActivity.class,
            withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    final Handler ui = mock(Handler.class);
    final StreamEngine engine = mock(StreamEngine.class);
    final FrameLayout root = mock(FrameLayout.class), toolbar = mock(FrameLayout.class);

    Player() throws Exception {
      field(activity, "ui", ui);
      field(activity, "engine", engine);
      field(activity, "root", root);
      field(activity, "toolbar", toolbar);
      field(activity, "playing", true);
      field(activity, "encoder", encoder());
      field(activity, "physicalInput", true);
      field(activity, "touchInput", true);
      field(activity, "videoWidth", 1280);
      field(activity, "videoHeight", 720);
      field(activity, "cursorView", mock(ImageView.class));
      when(root.getWidth()).thenReturn(1280);
      when(root.getHeight()).thenReturn(960);
      when(toolbar.getVisibility()).thenReturn(View.GONE);
      when(toolbar.getChildAt(0)).thenReturn(mock(LinearLayout.class));
    }

    List<byte[]> packets() {
      ArgumentCaptor<byte[]> capture = ArgumentCaptor.forClass(byte[].class);
      verify(engine, atLeastOnce()).input(capture.capture());
      return capture.getAllValues();
    }
  }

  @Test
  public void capturedMouseIsCoalescedAndPositionIsFlushedBeforeClick() throws Exception {
    Player p = new Player();
    MotionEvent move = mock(MotionEvent.class);
    when(move.getActionMasked()).thenReturn(MotionEvent.ACTION_MOVE);
    when(move.getX()).thenReturn(2.25f);
    when(move.getY()).thenReturn(-1f);
    for (int i = 0; i < 4; i++) assertTrue(p.activity.onCapturedPointerEvent(move));
    verifyNoInteractions(p.engine);
    verify(p.ui, times(1)).postDelayed(any(Runnable.class), eq(16L));
    call(p.activity, "pointerButtons", int.class, 1);
    List<byte[]> packets = p.packets();
    assertEquals(2, packets.size());
    assertEquals(7, le(packets.get(0)).getInt(0));
    assertEquals(9, be(packets.get(0)).getShort(4));
    assertEquals(-4, be(packets.get(0)).getShort(6));
    assertEquals(8, le(packets.get(1)).getInt(0));
  }

  private MotionEvent touch(int action, float x, float y) {
    MotionEvent e = mock(MotionEvent.class);
    when(e.getSource()).thenReturn(InputDevice.SOURCE_TOUCHSCREEN);
    when(e.getActionMasked()).thenReturn(action);
    when(e.getX()).thenReturn(x);
    when(e.getY()).thenReturn(y);
    when(e.getX(0)).thenReturn(x);
    when(e.getY(0)).thenReturn(y);
    when(e.getPointerId(0)).thenReturn(1);
    when(e.findPointerIndex(1)).thenReturn(0);
    return e;
  }

  @Test
  public void touchTapAndDragUseVideoCoordinatesAndReleaseTheButton() throws Exception {
    Player p = new Player();
    assertFalse(
        (Boolean)
            call(
                p.activity,
                "pointerTouch",
                MotionEvent.class,
                touch(MotionEvent.ACTION_DOWN, 500, 30)));
    verifyNoInteractions(p.engine);
    assertTrue(
        (Boolean)
            call(
                p.activity,
                "pointerTouch",
                MotionEvent.class,
                touch(MotionEvent.ACTION_DOWN, 640, 480)));
    call(p.activity, "pointerTouch", MotionEvent.class, touch(MotionEvent.ACTION_MOVE, 700, 500));
    call(p.activity, "pointerTouch", MotionEvent.class, touch(MotionEvent.ACTION_UP, 700, 500));
    List<byte[]> packets = p.packets();
    assertEquals(5, le(packets.get(0)).getInt(0));
    assertEquals(640, be(packets.get(0)).getShort(4));
    assertEquals(360, be(packets.get(0)).getShort(6));
    assertEquals(8, le(packets.get(1)).getInt(0));
    assertEquals(9, le(packets.get(packets.size() - 1)).getInt(0));
    assertEquals(0, get(p.activity, "mouseButtons"));
    assertEquals(-1, get(p.activity, "touchPointerId"));
  }

  @Test
  public void openingAnOverlayReleasesEveryHeldMouseButtonAndKeyboardKey() throws Exception {
    Player p = new Player();
    field(p.activity, "mouseButtons", 3);
    @SuppressWarnings("unchecked")
    Map<Integer, int[]> keys = (Map<Integer, int[]>) get(p.activity, "heldKeys");
    keys.put(17, new int[] {29, 2});
    keys.put(65, new int[] {30, 2});
    call(p.activity, "suspendControls");
    List<byte[]> packets = p.packets();
    assertEquals(9, le(packets.get(0)).getInt(0));
    assertEquals(9, le(packets.get(1)).getInt(0));
    assertEquals(4, le(packets.get(2)).getInt(0));
    assertEquals(4, le(packets.get(3)).getInt(0));
    assertEquals(12, le(packets.get(4)).getInt(0)); // neutral gamepad
    assertTrue(keys.isEmpty());
    assertEquals(0, get(p.activity, "mouseButtons"));
  }

  @Test
  public void backDisablesVirtualMouseThenOpensTheCompactBarOnTheNextPress() throws Exception {
    Player p = new Player();
    field(p.activity, "virtualMouse", true);
    p.activity.onBackPressed();
    assertEquals(false, get(p.activity, "virtualMouse"));
    verify(p.toolbar, never()).setVisibility(View.VISIBLE);
    p.activity.onBackPressed();
    verify(p.toolbar).setVisibility(View.VISIBLE);
    assertEquals(true, get(p.activity, "controlsSuspended"));
  }

  @Test
  public void virtualPointerHasNoIdleAnimationOrInputLoop() throws Exception {
    Player p = new Player();
    field(p.activity, "virtualMouse", true);
    ((Runnable) get(p.activity, "virtualTick")).run();
    verify(p.ui, never()).postDelayed(any(Runnable.class), anyLong());
    verifyNoInteractions(p.engine);
  }

  @Test
  public void virtualRemoteOkSendsRealAbsolutePositionAndClickDownUp() throws Exception {
    Player p = new Player();
    field(p.activity, "virtualMouse", true);
    KeyEvent e = mock(KeyEvent.class);
    when(e.getKeyCode()).thenReturn(KeyEvent.KEYCODE_DPAD_CENTER);
    when(e.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    call(p.activity, "virtualKey", KeyEvent.class, e);
    when(e.getAction()).thenReturn(KeyEvent.ACTION_UP);
    call(p.activity, "virtualKey", KeyEvent.class, e);
    List<byte[]> packets = p.packets();
    assertEquals(5, le(packets.get(0)).getInt(0));
    assertEquals(8, le(packets.get(1)).getInt(0));
    assertEquals(5, le(packets.get(2)).getInt(0));
    assertEquals(9, le(packets.get(3)).getInt(0));
  }

  @Test
  public void physicalMouseToggleDoesNotDisableTouchAndKeyboardMappingIncludesPcKeys()
      throws Exception {
    Player p = new Player();
    field(p.activity, "physicalInput", false);
    assertFalse(p.activity.onCapturedPointerEvent(touch(MotionEvent.ACTION_MOVE, 2, 3)));
    assertTrue(
        (Boolean)
            call(
                p.activity,
                "pointerTouch",
                MotionEvent.class,
                touch(MotionEvent.ACTION_DOWN, 200, 400)));
    assertEquals(46, StreamActivity.vk(KeyEvent.KEYCODE_FORWARD_DEL));
    assertEquals(219, StreamActivity.vk(KeyEvent.KEYCODE_LEFT_BRACKET));
    assertEquals(103, StreamActivity.vk(KeyEvent.KEYCODE_NUMPAD_7));
    assertEquals(91, StreamActivity.vk(KeyEvent.KEYCODE_META_LEFT));
  }
}
