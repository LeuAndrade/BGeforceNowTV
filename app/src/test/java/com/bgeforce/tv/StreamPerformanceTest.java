package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.view.*;
import org.junit.Test;

public class StreamPerformanceTest {
  @Test
  public void androidTenRequestsTheLowLatencyWifiLock() {
    WifiManager manager = mock(WifiManager.class);
    WifiManager.WifiLock lock = mock(WifiManager.WifiLock.class);
    when(manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "BGFN-stream"))
        .thenReturn(lock);
    assertSame(lock, StreamPerformance.wifi(manager, 29));
    verify(lock).acquire();
    verify(lock).setReferenceCounted(false);
    verify(manager, never()).createWifiLock(eq(WifiManager.WIFI_MODE_FULL_HIGH_PERF), anyString());
  }

  @Test
  public void oldAndroidKeepsTheCompatibleWifiLock() {
    WifiManager manager = mock(WifiManager.class);
    WifiManager.WifiLock lock = mock(WifiManager.WifiLock.class);
    when(manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BGFN-stream"))
        .thenReturn(lock);
    assertSame(lock, StreamPerformance.wifi(manager, 23));
    verify(lock).acquire();
    verify(manager, never())
        .createWifiLock(eq(WifiManager.WIFI_MODE_FULL_LOW_LATENCY), anyString());
  }

  @Test
  public void rejectedLowLatencyFallsBackWithoutFailingTheGame() {
    WifiManager manager = mock(WifiManager.class);
    WifiManager.WifiLock lock = mock(WifiManager.WifiLock.class);
    when(manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "BGFN-stream"))
        .thenThrow(new IllegalArgumentException("unsupported"));
    when(manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BGFN-stream"))
        .thenReturn(lock);
    assertSame(lock, StreamPerformance.wifi(manager, 29));
    verify(lock).acquire();
  }

  @Test
  public void wifiUnavailableOrDeniedDoesNotPreventStreaming() {
    assertNull(StreamPerformance.wifi(null, 29));
    WifiManager manager = mock(WifiManager.class);
    when(manager.createWifiLock(anyInt(), anyString())).thenThrow(new SecurityException("denied"));
    assertNull(StreamPerformance.wifi(manager, 35));
  }

  @Test
  public void displayHintRequestsTheChosenFpsWithoutForcingAModeIdOrResolution() {
    Activity activity = mock(Activity.class);
    Window window = mock(Window.class);
    WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    when(activity.getWindow()).thenReturn(window);
    when(window.getAttributes()).thenReturn(params);
    StreamPerformance.window(activity, 60);
    assertEquals(60, params.preferredRefreshRate, 0);
    assertEquals(0, params.preferredDisplayModeId);
    verify(window).setAttributes(params);
  }

  @Test
  public void rejectedDisplayHintDoesNotPreventStreaming() {
    Activity activity = mock(Activity.class);
    when(activity.getWindow()).thenThrow(new IllegalStateException("vendor"));
    StreamPerformance.window(activity, 60);
  }
}
