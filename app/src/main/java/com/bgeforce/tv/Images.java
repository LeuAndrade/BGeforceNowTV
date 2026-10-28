package com.bgeforce.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.*;
import android.graphics.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import okhttp3.*;

/** Bounded downloads and RGB565 sampled artwork, sized to the actual card. */
public final class Images implements AutoCloseable {
  private static final Map<File, Cache> DISK_CACHES = new HashMap<>();

  private static synchronized Cache sharedDiskCache(File directory, long bytes) {
    Cache cache = DISK_CACHES.get(directory);
    if (cache == null) {
      cache = new Cache(directory, bytes);
      DISK_CACHES.put(directory, cache);
    }
    return cache;
  }

  private final Activity activity;
  private final OkHttpClient http;
  private final LruCache<String, Bitmap> cache;
  private final ThreadPoolExecutor jobs =
      new ThreadPoolExecutor(
          2,
          2,
          15,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          new ThreadPoolExecutor.DiscardOldestPolicy());
  private volatile int generation;
  private volatile boolean closed;

  public Images(Activity c) {
    activity = c;
    ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
    int budget = am.isLowRamDevice() ? 3 * 1024 * 1024 : 6 * 1024 * 1024;
    cache =
        new LruCache<String, Bitmap>(budget) {
          @Override
          protected int sizeOf(String k, Bitmap b) {
            return b.getAllocationByteCount();
          }
        };
    boolean player = c instanceof StreamActivity;
    http =
        new OkHttpClient.Builder()
            .cache(
                sharedDiskCache(
                    new File(c.getCacheDir(), player ? "art-player" : "art"),
                    (player ? 8L : 24L) * 1024 * 1024))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(2, 15, TimeUnit.SECONDS))
            .build();
  }

  public void load(ImageView v, String url, int width, int height) {
    String key = url + "@" + width + "x" + height;
    v.setTag(key);
    v.setImageDrawable(null);
    v.setBackgroundColor(Ui.PANEL);
    if (closed || !url.startsWith("https://")) return;
    Bitmap hit = cache.get(key);
    if (hit != null) {
      v.setImageBitmap(hit);
      return;
    }
    int gen = generation;
    jobs.execute(
        () -> {
          if (closed || gen != generation) return;
          try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return;
            byte[] data = Catalog.readBytes(r.body().byteStream(), 5 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            o.inSampleSize = 1;
            while (o.outWidth / o.inSampleSize > width * 2
                || o.outHeight / o.inSampleSize > height * 2) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null || gen != generation || closed) return;
            cache.put(key, bmp);
            activity.runOnUiThread(
                () -> {
                  if (!closed && gen == generation && key.equals(v.getTag())) v.setImageBitmap(bmp);
                });
          } catch (Exception ignored) {
            /* The title remains usable when artwork cannot be downloaded. */
          }
        });
  }

  public void trim() {
    invalidate();
    NetworkCleanup.cancelCalls(http);
  }

  private void invalidate() {
    generation++;
    jobs.getQueue().clear();
    cache.evictAll();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    invalidate();
    NetworkCleanup.release(http, jobs);
  }
}
