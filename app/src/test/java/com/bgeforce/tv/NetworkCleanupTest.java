package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.NetworkOnMainThreadException;
import android.util.LruCache;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Guarded sockets model the Android TLS-close restriction reported by the real device. */
public class NetworkCleanupTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private void field(Class<?> type, Object target, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private Object field(Class<?> type, Object target, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private void drain() throws Exception {
    NetworkCleanup.execute(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private static final class GuardedConnection {
    final Thread ui = Thread.currentThread();
    final AtomicReference<Thread> closer = new AtomicReference<>();
    final AtomicInteger violations = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch allowClose = new CountDownLatch(1);
    final OkHttpClient client = mock(OkHttpClient.class);
    final Dispatcher dispatcher = mock(Dispatcher.class);
    final ConnectionPool pool = mock(ConnectionPool.class);
    final Call call = mock(Call.class);
    volatile boolean block;
    final Socket socket =
        new Socket() {
          @Override
          public void close() throws IOException {
            if (Thread.currentThread() == ui) {
              violations.incrementAndGet();
              throw new NetworkOnMainThreadException();
            }
            closer.set(Thread.currentThread());
            entered.countDown();
            if (block)
              try {
                if (!allowClose.await(5, TimeUnit.SECONDS))
                  throw new IOException("Close gate timed out");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
            super.close();
          }
        };

    GuardedConnection() {
      when(client.dispatcher()).thenReturn(dispatcher);
      when(client.connectionPool()).thenReturn(pool);
      when(dispatcher.queuedCalls()).thenReturn(Collections.emptyList());
      when(dispatcher.runningCalls()).thenReturn(Collections.singletonList(call));
      doAnswer(
              invocation -> {
                socket.close();
                return null;
              })
          .when(call)
          .cancel();
      doAnswer(
              invocation -> {
                socket.close();
                return null;
              })
          .when(dispatcher)
          .cancelAll();
      doAnswer(
              invocation -> {
                socket.close();
                return null;
              })
          .when(pool)
          .evictAll();
    }

    void assertOffUi() {
      assertEquals(0, violations.get());
      assertNotNull(closer.get());
      assertNotSame(ui, closer.get());
    }
  }

  private MainActivity catalog(GuardedConnection connection) throws Exception {
    MainActivity activity =
        mock(MainActivity.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    field(MainActivity.class, activity, "api", new Api(null, null, connection.client));
    field(MainActivity.class, activity, "images", mock(Images.class));
    field(MainActivity.class, activity, "streaming", true);
    return activity;
  }

  private ThreadPoolExecutor ownedJobs(Thread ui, AtomicReference<Thread> stopper) {
    ThreadPoolExecutor jobs = mock(ThreadPoolExecutor.class);
    when(jobs.getQueue()).thenReturn(new LinkedBlockingQueue<>());
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == ui) throw new NetworkOnMainThreadException();
              stopper.set(Thread.currentThread());
              return Collections.emptyList();
            })
        .when(jobs)
        .shutdownNow();
    return jobs;
  }

  @Test
  public void clickingPlayStopsCatalogWithoutClosingTlsOnUiThread() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    MainActivity activity = catalog(connection);
    ThreadPoolExecutor jobs = (ThreadPoolExecutor) field(MainActivity.class, activity, "jobs");
    connection.block = true;
    try {
      activity.onStop();
      // The lifecycle callback already returned while the socket close is still blocked.
      assertTrue(connection.entered.await(5, TimeUnit.SECONDS));
      connection.assertOffUi();
      assertFalse(jobs.isShutdown()); // The catalog can resume after the game.
    } finally {
      connection.allowClose.countDown();
      drain();
      jobs.shutdownNow(); // No jobs/network calls were started on this fixture executor.
    }
  }

  @Test
  public void destroyingCatalogInterruptsAndClosesOnlyOnWorker() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    MainActivity activity = catalog(connection);
    AtomicReference<Thread> stopper = new AtomicReference<>();
    ThreadPoolExecutor jobs = ownedJobs(connection.ui, stopper);
    field(MainActivity.class, activity, "jobs", jobs);
    activity.onDestroy();
    drain();
    connection.assertOffUi();
    assertNotNull(stopper.get());
    assertNotSame(connection.ui, stopper.get());
    verify(jobs).shutdownNow();
  }

  private Images loader(
      GuardedConnection connection, ThreadPoolExecutor jobs, LruCache<String, Bitmap> bitmaps)
      throws Exception {
    Images images = mock(Images.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    field(Images.class, images, "http", connection.client);
    field(Images.class, images, "jobs", jobs);
    field(Images.class, images, "cache", bitmaps);
    return images;
  }

  @Test
  public void imageCloseClearsUiAndReleasesNetworkOffUiOnce() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    AtomicReference<Thread> stopper = new AtomicReference<>();
    ThreadPoolExecutor jobs = ownedJobs(connection.ui, stopper);
    @SuppressWarnings("unchecked")
    LruCache<String, Bitmap> bitmaps = mock(LruCache.class);
    doAnswer(
            invocation -> {
              assertSame(connection.ui, Thread.currentThread());
              return null;
            })
        .when(bitmaps)
        .evictAll();
    Images images = loader(connection, jobs, bitmaps);
    images.close();
    images.close();
    assertEquals(true, field(Images.class, images, "closed"));
    verify(bitmaps).evictAll();
    drain();
    connection.assertOffUi();
    verify(connection.call).cancel();
    verify(connection.pool).evictAll();
    verify(jobs).shutdownNow();
    assertNotSame(connection.ui, stopper.get());
  }

  @Test
  public void trimmingImagesCancelsOffUiWithoutClosingTheReusableLoader() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    AtomicReference<Thread> stopper = new AtomicReference<>();
    ThreadPoolExecutor jobs = ownedJobs(connection.ui, stopper);
    @SuppressWarnings("unchecked")
    LruCache<String, Bitmap> bitmaps = mock(LruCache.class);
    Images images = loader(connection, jobs, bitmaps);
    images.trim();
    drain();
    connection.assertOffUi();
    assertEquals(false, field(Images.class, images, "closed"));
    verify(jobs, never()).shutdownNow();
    verify(connection.pool, never()).evictAll();
  }

  @Test
  public void deferredCancellationDoesNotCancelCallsCreatedAfterResume() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1), resume = new CountDownLatch(1);
    NetworkCleanup.execute(
        () -> {
          occupied.countDown();
          try {
            resume.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(5, TimeUnit.SECONDS));
    GuardedConnection connection = new GuardedConnection();
    Call newer = mock(Call.class);
    try {
      Future<?> canceled = NetworkCleanup.cancelCalls(connection.client);
      when(connection.dispatcher.runningCalls()).thenReturn(Collections.singletonList(newer));
      resume.countDown();
      canceled.get(5, TimeUnit.SECONDS);
      verify(connection.call).cancel();
      verify(newer, never()).cancel();
      connection.assertOffUi();
    } finally {
      resume.countDown();
      drain();
    }
  }

  @Test
  public void oneCleanupFailureDoesNotSkipRemainingResources() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    Call failed = mock(Call.class);
    doThrow(new IllegalStateException("synthetic close failure")).when(failed).cancel();
    when(connection.dispatcher.runningCalls()).thenReturn(Arrays.asList(failed, connection.call));
    AtomicReference<Thread> stopper = new AtomicReference<>();
    ThreadPoolExecutor jobs = ownedJobs(connection.ui, stopper);
    NetworkCleanup.release(connection.client, jobs).get(5, TimeUnit.SECONDS);
    connection.assertOffUi();
    verify(jobs).shutdownNow();
    verify(connection.pool).evictAll();
  }

  @Test
  public void imageLoaderRecreationSharesUsableDiskCache() throws Exception {
    Activity activity = mock(Activity.class);
    when(activity.getSystemService(Context.ACTIVITY_SERVICE))
        .thenReturn(mock(ActivityManager.class));
    when(activity.getCacheDir()).thenReturn(temporary.newFolder("artwork"));
    Images first = new Images(activity), second = new Images(activity);
    Cache cache = ((OkHttpClient) field(Images.class, first, "http")).cache();
    try {
      assertSame(cache, ((OkHttpClient) field(Images.class, second, "http")).cache());
      first.close();
      drain();
      assertFalse(cache.isClosed());
      cache.flush();
      assertEquals(0, cache.size());
      Images third = new Images(activity);
      assertSame(cache, ((OkHttpClient) field(Images.class, third, "http")).cache());
      third.close();
    } finally {
      first.close();
      second.close();
      drain();
      cache.close();
    }
  }

  @Test
  public void signalingCloseRunsOffUiAndIsIdempotent() throws Exception {
    GuardedConnection connection = new GuardedConnection();
    Signaling signaling = mock(Signaling.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    WebSocket socket = mock(WebSocket.class);
    doAnswer(
            invocation -> {
              connection.socket.close();
              return true;
            })
        .when(socket)
        .close(1000, "closed");
    field(Signaling.class, signaling, "timer", timer);
    field(Signaling.class, signaling, "socket", socket);
    signaling.close();
    signaling.close();
    drain();
    connection.assertOffUi();
    verify(timer).shutdownNow();
    verify(socket).close(1000, "closed");
  }
}
