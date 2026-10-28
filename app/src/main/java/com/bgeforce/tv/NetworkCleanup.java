package com.bgeforce.tv;

import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/** TLS socket close/cancel may perform network I/O, including when releasing idle connections. */
final class NetworkCleanup {
  private static final ExecutorService IO =
      new ThreadPoolExecutor(
          0,
          1,
          15,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(),
          runnable -> {
            Thread thread = new Thread(runnable, "BGFN-network-cleanup");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
          });

  private static List<Call> snapshot(OkHttpClient client) {
    if (client == null) return new ArrayList<>();
    Set<Call> calls = new LinkedHashSet<>(client.dispatcher().queuedCalls());
    calls.addAll(client.dispatcher().runningCalls());
    return new ArrayList<>(calls);
  }

  static Future<?> cancelCalls(OkHttpClient client) {
    List<Call> calls = snapshot(client);
    return execute(() -> cancel(calls));
  }

  static Future<?> release(OkHttpClient client, ExecutorService owner) {
    // Capture now: a deferred cancelAll could cancel new calls after the UI resumes or logs in.
    List<Call> calls = snapshot(client);
    ConnectionPool pool = client == null ? null : client.connectionPool();
    return execute(
        () -> {
          cancel(calls);
          if (owner != null) safely(owner::shutdownNow);
          if (pool != null) safely(pool::evictAll);
          // Image Cache instances belong to the application, not to a transient screen/loader.
        });
  }

  private static void cancel(List<Call> calls) {
    for (Call call : calls) safely(call::cancel);
  }

  static Future<?> execute(Runnable cleanup) {
    return IO.submit(() -> safely(cleanup));
  }

  private static void safely(Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException error) {
      Log.w("BGFN", "Connection cleanup: " + error.getClass().getSimpleName());
    }
  }
}
