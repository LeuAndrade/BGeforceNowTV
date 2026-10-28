package com.bgeforce.tv;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.view.*;

/** Public, optional platform hints. Failure to grant a hint never stops a stream. */
final class StreamPerformance {
  static void window(Activity activity, int fps) {
    try {
      WindowManager.LayoutParams params = activity.getWindow().getAttributes();
      params.preferredRefreshRate = fps;
      activity.getWindow().setAttributes(params);
    } catch (RuntimeException ignored) {
    }
  }

  static void surface(SurfaceView view, int fps) {
    view.getHolder()
        .addCallback(
            new SurfaceHolder.Callback() {
              public void surfaceCreated(SurfaceHolder h) {
                hint(h);
              }

              public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {
                hint(h);
              }

              public void surfaceDestroyed(SurfaceHolder h) {}

              private void hint(SurfaceHolder h) {
                if (Build.VERSION.SDK_INT < 30) return;
                try {
                  Surface s = h.getSurface();
                  if (s != null && s.isValid())
                    s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                } catch (RuntimeException ignored) {
                }
              }
            });
  }

  @android.annotation.SuppressLint("InlinedApi") // sdk is Build.VERSION.SDK_INT in production.
  static WifiManager.WifiLock wifi(WifiManager manager, int sdk) {
    if (manager == null) return null;
    int[] modes =
        sdk >= 29
            ? new int[] {
              WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            : new int[] {WifiManager.WIFI_MODE_FULL_HIGH_PERF};
    for (int mode : modes) {
      WifiManager.WifiLock lock = null;
      try {
        lock = manager.createWifiLock(mode, "BGFN-stream");
        lock.setReferenceCounted(false);
        lock.acquire();
        return lock;
      } catch (RuntimeException ignored) {
        if (lock != null)
          try {
            if (lock.isHeld()) lock.release();
          } catch (RuntimeException ignoredAgain) {
          }
      }
    }
    return null;
  }
}
