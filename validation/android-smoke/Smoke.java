package com.bgeforce.tv.validation;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Runs against the signed release. Uses fake media; never contacts or launches a GFN session. */
public final class Smoke extends Instrumentation {
  private Bundle arguments;
  private Activity activity;
  private final List<byte[]> packets = Collections.synchronizedList(new ArrayList<byte[]>());
  private int checks;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  private void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  private static Object get(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private void ui(Runnable action) {
    runOnMainSync(action);
    waitForIdleSync();
  }

  private static EditText edit(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        EditText found = edit(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private static int gamepadMask(byte[] packet) {
    int offset = packet.length > 12 && packet[0] == 0x23 && packet[9] == 0x21 ? 12 : 0;
    if (packet.length < offset + 38 || packet[offset] != 12) return -1;
    return (packet[offset + 12] & 255) | ((packet[offset + 13] & 255) << 8);
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      Context context = getTargetContext();
      android.content.SharedPreferences probe =
          context.getSharedPreferences("bgfn_update_probe", 0);
      if ("seed".equals(arguments.getString("mode"))) {
        check(
            probe.edit().putString("sentinel", "v13-to-v14-same-key").commit(),
            "write update sentinel");
        result.putString("result", "seed saved");
      } else {
        check(
            "v13-to-v14-same-key".equals(probe.getString("sentinel", "")),
            "update must preserve app data");
        ClassLoader loader = context.getClassLoader();
        Class<?> settingsClass = loader.loadClass("com.bgeforce.tv.Settings");
        Object settings = settingsClass.getConstructor(Context.class).newInstance(context);
        check(
            ((Integer) settingsClass.getMethod("width").invoke(settings)) == 1280, "default width");
        check(
            ((Integer) settingsClass.getMethod("height").invoke(settings)) == 720,
            "default height");
        check(((Integer) settingsClass.getMethod("fps").invoke(settings)) == 60, "default fps");
        Application app = (Application) context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks =
            new Application.ActivityLifecycleCallbacks() {
              public void onActivityCreated(Activity a, Bundle state) {
                if (!a.getClass().getName().equals("com.bgeforce.tv.StreamActivity")) return;
                try {
                  set(
                      a,
                      "network",
                      new AbstractExecutorService() {
                        public void execute(
                            Runnable r) {} // Test only: no real session creation/discovery.

                        public void shutdown() {}

                        public List<Runnable> shutdownNow() {
                          return Collections.emptyList();
                        }

                        public boolean isShutdown() {
                          return false;
                        }

                        public boolean isTerminated() {
                          return false;
                        }

                        public boolean awaitTermination(long t, TimeUnit u) {
                          return true;
                        }
                      });
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              public void onActivityStarted(Activity a) {}

              public void onActivityResumed(Activity a) {}

              public void onActivityPaused(Activity a) {}

              public void onActivityStopped(Activity a) {}

              public void onActivitySaveInstanceState(Activity a, Bundle b) {}

              public void onActivityDestroyed(Activity a) {}
            };
        app.registerActivityLifecycleCallbacks(callbacks);
        Intent intent =
            new Intent()
                .setClassName("com.bgeforce.tv.main", "com.bgeforce.tv.StreamActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(
            "game",
            "{\"id\":\"smoke\",\"title\":\"Validation fixture\",\"variants\":[{\"id\":\"123\"}]}");
        intent.putExtra("variant", "123");
        activity = startActivitySync(intent);
        waitForIdleSync();
        app.unregisterActivityLifecycleCallbacks(callbacks);
        final Class<?> streamEngine = loader.loadClass("com.bgeforce.tv.StreamEngine");
        final Object fake =
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {streamEngine},
                (p, m, args) -> {
                  if (m.getName().equals("input")) packets.add(((byte[]) args[0]).clone());
                  if (m.getName().equals("view")) return new View(context);
                  return null;
                });
        ui(
            () -> {
              try {
                set(activity, "engine", fake);
                activity.getClass().getMethod("firstFrame").invoke(activity);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        ui(() -> activity.onBackPressed());
        final ViewGroup toolbar = (ViewGroup) get(activity, "toolbar");
        final ViewGroup row = (ViewGroup) toolbar.getChildAt(0);
        check(toolbar.getVisibility() == View.VISIBLE, "back opens compact bar");
        check(row.getChildCount() == 5, "five compact bar controls");
        check(
            toolbar.getX() > 0 && toolbar.getY() > 0 && toolbar.getX() < 64 && toolbar.getY() < 64,
            "top left bar position");
        check(toolbar.getWidth() < 400 && toolbar.getHeight() < 100, "compact dimensions");
        ui(() -> row.getChildAt(1).performClick());
        check((Boolean) get(activity, "virtualMouse"), "virtual mouse enabled");
        ui(
            () -> {
              activity.dispatchKeyEvent(
                  new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
              activity.dispatchKeyEvent(
                  new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
            });
        check(packets.size() >= 3, "virtual pointer transmits real input packets");
        ui(() -> activity.onBackPressed());
        check(!(Boolean) get(activity, "virtualMouse"), "back disables virtual mouse");
        check(!activity.isFinishing(), "back does not close game activity");
        ui(() -> activity.onBackPressed());
        check(toolbar.getVisibility() == View.VISIBLE, "next back opens bar");
        ui(() -> row.getChildAt(0).performClick());
        final AlertDialog keyboard = (AlertDialog) get(activity, "keyboardDialog");
        check(keyboard != null && keyboard.isShowing(), "keyboard opens");
        ui(
            () -> {
              EditText text = edit(keyboard.getWindow().getDecorView());
              if (text == null) throw new AssertionError("text field");
              text.setText("teste");
              keyboard.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
        boolean textPacket = false;
        for (byte[] packet : packets) if (packet.length > 5 && packet[0] == 0x22) textPacket = true;
        check(textPacket, "keyboard submits text protocol packet");
        final Object playerSettings = get(activity, "settings");
        ui(
            () -> {
              try {
                settingsClass
                    .getMethod("set", String.class, boolean.class)
                    .invoke(playerSettings, "stats", true);
                JSONObject stats = new JSONObject();
                stats.put("fps", 40);
                stats.put("width", 1280);
                stats.put("height", 720);
                stats.put("mbps", 8);
                stats.put("decodeMs", 4);
                activity.getClass().getMethod("stats", JSONObject.class).invoke(activity, stats);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        String hud = ((TextView) get(activity, "hud")).getText().toString();
        check(
            hud.contains("60 FPS") && hud.contains("40.0 FPS"),
            "selected target and actual decoded fps are distinct");
        Class<?> peersClass = loader.loadClass("com.bgeforce.tv.SignalingPeer");
        Constructor<?> peersConstructor = peersClass.getDeclaredConstructor();
        peersConstructor.setAccessible(true);
        Object peers = peersConstructor.newInstance();
        Method next = peersClass.getDeclaredMethod("next", String.class, String.class);
        next.setAccessible(true);
        Object fresh = next.invoke(peers, "existing", "wss://game.nvidiagrid.net/nvst/");
        check(!(Boolean) get(fresh, "reconnect"), "first claimed transport is fresh");
        Object retry = next.invoke(peers, "existing", "wss://game.nvidiagrid.net/nvst/");
        check((Boolean) get(retry, "reconnect"), "same transport retry marks reconnect");
        check(get(fresh, "name").equals(get(retry, "name")), "retry keeps peer identity");
        Object moved = next.invoke(peers, "existing", "wss://new.nvidiagrid.net/nvst/");
        check(!(Boolean) get(moved, "reconnect"), "changed endpoint starts fresh");

        ui(() -> keyboard.dismiss());
        final long[] controllerDeadlines = new long[2];
        final int[] masks = new int[3];
        final boolean[] handled = {true};
        ui(
            () -> {
              packets.clear();
              long at = SystemClock.uptimeMillis();
              handled[0] &=
                  activity.dispatchKeyEvent(
                      new KeyEvent(
                          at,
                          at,
                          KeyEvent.ACTION_DOWN,
                          KeyEvent.KEYCODE_BUTTON_A,
                          0,
                          0,
                          777,
                          0,
                          0,
                          InputDevice.SOURCE_GAMEPAD));
              try {
                controllerDeadlines[0] = (Long) get(activity, "controllerDue");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              for (int i = 1; i <= 1000; i++)
                handled[0] &=
                    activity.dispatchKeyEvent(
                        new KeyEvent(
                            at,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_BUTTON_A,
                            i,
                            0,
                            777,
                            0,
                            0,
                            InputDevice.SOURCE_GAMEPAD));
              try {
                controllerDeadlines[1] = (Long) get(activity, "controllerDue");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              handled[0] &=
                  activity.dispatchKeyEvent(
                      new KeyEvent(
                          at,
                          SystemClock.uptimeMillis(),
                          KeyEvent.ACTION_UP,
                          KeyEvent.KEYCODE_BUTTON_A,
                          0,
                          0,
                          777,
                          0,
                          0,
                          InputDevice.SOURCE_GAMEPAD));
              int count = 0, firstMask = -1, lastMask = -1;
              for (byte[] packet : packets) {
                int mask = gamepadMask(packet);
                if (mask >= 0) {
                  if (count == 0) firstMask = mask;
                  lastMask = mask;
                  count++;
                }
              }
              masks[0] = count;
              masks[1] = firstMask;
              masks[2] = lastMask;
            });
        check(handled[0], "gamepad burst is handled within the player");
        check(
            controllerDeadlines[0] == controllerDeadlines[1],
            "repeated button down does not re-arm controller deadline");
        check(
            masks[0] == 2,
            "1000 repeated downs keep only press/release packets in the synchronous burst");
        check(masks[1] == 0x1000 && masks[2] == 0, "press and release keep exact gamepad state");
        final boolean[] dpadHandled = {true};
        ui(
            () -> {
              long at = SystemClock.uptimeMillis();
              dpadHandled[0] &=
                  activity.dispatchKeyEvent(
                      new KeyEvent(
                          at,
                          at,
                          KeyEvent.ACTION_DOWN,
                          KeyEvent.KEYCODE_DPAD_CENTER,
                          0,
                          0,
                          778,
                          0,
                          0,
                          InputDevice.SOURCE_DPAD));
              dpadHandled[0] &=
                  activity.dispatchKeyEvent(
                      new KeyEvent(
                          at,
                          at,
                          KeyEvent.ACTION_UP,
                          KeyEvent.KEYCODE_DPAD_CENTER,
                          0,
                          0,
                          778,
                          0,
                          0,
                          InputDevice.SOURCE_DPAD));
            });
        check(dpadHandled[0], "TV remote center is consumed by gameplay without focus navigation");
        result.putString(
            "result", "release UI/input/update smoke passed; fake media, no gameplay benchmark");
      }
      result.putInt("checks", checks);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("failure", android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
    }
  }
}
