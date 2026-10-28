package com.bgeforce.tv;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.webrtc.IceCandidate;

/** One session, one renderer and a single polling chain; closing cancels all three. */
public final class StreamActivity extends Activity
    implements StreamEngine.Events, InputManager.InputDeviceListener {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final InputEncoder encoder = new InputEncoder();
  private Api api;
  private Settings settings;
  private L l;
  private Game game;
  private SessionClient sessions;
  private volatile SessionClient.Seat seat;
  private Signaling signaling;
  private SessionClient.Seat transportSeat;
  private int endpointHandovers;
  private final QueueProgress queueProgress = new QueueProgress();
  private StreamEngine engine;
  private Images images;
  private FrameLayout root, loading, toolbar;
  private TextView queue, subtitle, hud;
  private volatile boolean closed;
  private boolean playing, started, failed, polling, micAtStart;
  private int keyButtons, hatButtons, lt, rt, lx, ly, rx, ry;
  private long createdAt, offerAt;
  private int pollErrors;
  private volatile int transportGeneration;
  private boolean ageDialogShowing, controlsSuspended;
  private final SignalingPeer signalingPeer = new SignalingPeer();
  private final PadChanges padChanges = new PadChanges();
  private final Set<Integer> padDevices = new HashSet<>();
  private boolean controllerPosted;
  private long controllerDue = Long.MAX_VALUE;
  private float stickDeadzone;
  private AlertDialog keyboardDialog;
  private PopupMenu streamMenu;
  private final PointerMotion pointerMotion = new PointerMotion();
  private final Map<Integer, int[]> heldKeys = new LinkedHashMap<>();
  private int mouseButtons, touchPointerId = -1, virtualDirections;
  private boolean physicalInput,
      captureMouse,
      touchInput,
      pointerPosted,
      virtualMouse,
      virtualPosted;
  private boolean mouseConnected, keyboardConnected, gameAudio = true, endSession;
  private float cursorX = .5f, cursorY = .5f, virtualAxisX, virtualAxisY;
  private long virtualAt;
  private int videoWidth, videoHeight;
  private ImageView cursorView;
  private InputManager inputManager;
  private ViewportCache viewport = new ViewportCache();

  private static final class ViewportCache {
    int w, h, videoW, videoH;
    VideoViewport value;

    VideoViewport get(int width, int height, int vw, int vh) {
      if (value == null || w != width || h != height || videoW != vw || videoH != vh) {
        w = width;
        h = height;
        videoW = vw;
        videoH = vh;
        value = new VideoViewport(w, h, vw, vh);
      }
      return value;
    }
  }

  private AudioManager audio;
  private AudioFocusRequest focus;
  private WifiManager.WifiLock wifi;
  private final Runnable backgroundExit =
      () -> {
        if (!closed && !isChangingConfigurations()) {
          close();
          finish();
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    Ui.fullscreen(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    api = new Api(this);
    settings = api.settings;
    stickDeadzone = settings.number("deadzone", 12) / 100f;
    physicalInput = settings.flag("keyboardMouse", true);
    captureMouse = settings.flag("captureMouse", false);
    touchInput = settings.flag("touchInput", true);
    videoWidth = settings.width();
    videoHeight = settings.height();
    l = new L(settings.locale());
    sessions = new SessionClient(api);
    images = new Images(this);
    String raw = getIntent().getStringExtra("game");
    if (raw == null) {
      finish();
      return;
    }
    game = new Game(Json.parse(raw));
    CrashTrace.begin(
        settings.engine()
            + "/"
            + settings.decoderPath()
            + " · "
            + settings.width()
            + "x"
            + settings.height()
            + "/"
            + settings.fps()
            + " · "
            + settings.codec());
    root = new FrameLayout(this);
    root.setLayoutDirection(
        View.LAYOUT_DIRECTION_LTR); // Video coordinates always originate at physical top-left.
    root.setBackgroundColor(android.graphics.Color.BLACK);
    root.setFocusableInTouchMode(true);
    setContentView(root);
    inputManager = (InputManager) getSystemService(INPUT_SERVICE);
    if (inputManager != null) inputManager.registerInputDeviceListener(this, ui);
    detectDevices(false);
    if (Build.VERSION.SDK_INT >= 26)
      root.setOnCapturedPointerListener((v, e) -> onCapturedPointerEvent(e));
    loading = new FrameLayout(this);
    ImageView art = new ImageView(this);
    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
    loading.addView(art, new FrameLayout.LayoutParams(-1, -1));
    images.load(
        art, game.hero, settings.constrained ? 640 : 1100, settings.constrained ? 360 : 620);
    View fade = new View(this);
    fade.setBackground(
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[] {0xef050b10, 0xa6050b10, 0xef050b10}));
    loading.addView(fade, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout copy = Ui.column(this);
    copy.setGravity(Gravity.CENTER);
    copy.setPadding(Ui.dp(this, 60), 0, Ui.dp(this, 60), 0);
    loading.addView(copy, new FrameLayout.LayoutParams(-1, -1));
    copy.addView(Ui.text(this, "BGeforce Now TV  /  GEFORCE NOW", 12, Ui.ACCENT));
    Ui.gap(copy, 16);
    copy.addView(Ui.title(this, game.title, 34));
    Ui.gap(copy, 26);
    ProgressBar progress = new ProgressBar(this);
    copy.addView(progress, Ui.lp(this, 44, 44));
    Ui.gap(copy, 14);
    queue = Ui.title(this, l.t("loading"), 26);
    copy.addView(queue);
    Ui.gap(copy, 10);
    subtitle =
        Ui.text(
            this,
            settings.engine()
                + "  •  "
                + settings.width()
                + "×"
                + settings.height()
                + "  •  "
                + settings.fps()
                + " FPS  •  "
                + settings.codec(),
            13,
            Ui.MUTED);
    copy.addView(subtitle);
    Ui.gap(copy, 26);
    copy.addView(Ui.button(this, l.t("cancel"), this::confirmExit));
    root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    hud = Ui.text(this, "", 11, Ui.TEXT);
    hud.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
    hud.setBackground(Ui.bg(0xd9050b10, Ui.dp(this, 6)));
    FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    hp.setMargins(0, Ui.dp(this, 16), Ui.dp(this, 16), 0);
    root.addView(hud, hp);
    hud.setVisibility(View.GONE);
    createToolbar();
    cursorView = new ImageView(this);
    cursorView.setImageDrawable(new StreamGlyph("cursor"));
    cursorView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    root.addView(cursorView, new FrameLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 29)));
    cursorView.setVisibility(View.GONE);
    createdAt = SystemClock.elapsedRealtime();
    ui.postDelayed(watchdog, 1000);
    launch(false);
  }

  private void launch(boolean recovering) {
    if (closed) return;
    network.execute(
        () -> {
          try {
            if (!api.restore()) throw new IllegalStateException(l.t("login"));
            Game.Variant variant = null;
            String requested = getIntent().getStringExtra("variant");
            for (Game.Variant v : game.variants) if (v.id.equals(requested)) variant = v;
            if (variant == null) throw new IllegalStateException("Missing game variant");
            SessionClient.Seat fresh;
            String resumeId = getIntent().getStringExtra("resumeId");
            if (resumeId != null) {
              fresh =
                  sessions.resume(getIntent().getStringExtra("resumeBase"), resumeId, requested);
            } else if (recovering) fresh = sessions.recoverOrCreate(game, variant);
            else {
              SessionClient.Active existing = null;
              try {
                existing = sessions.findActive(requested);
              } catch (IOException ignored) {
                /* Preserve fresh launches during discovery outages. */
              }
              fresh =
                  existing == null
                      ? sessions.create(game, variant)
                      : sessions.resume(existing.seat.base, existing.seat.id, requested);
            }
            seat = fresh;
            if (closed) {
              if (endSession) stopSeat(fresh);
              return;
            }
            settings
                .prefs
                .edit()
                .putString("resumeGameAccount", api.userId())
                .putString("resumeGame", game.raw.toString())
                .apply();
            ui.post(() -> updateSeat(fresh));
          } catch (Api.AgeRequired required) {
            ui.post(() -> confirmAge(recovering));
          } catch (Exception error) {
            error(error.getMessage());
          }
        });
  }

  private void confirmAge(boolean recovering) {
    if (closed || isFinishing() || ageDialogShowing) return;
    ageDialogShowing = true;
    boolean pt = settings.locale().getLanguage().equals("pt");
    EditText input = new EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setSingleLine(true);
    input.setHint(pt ? "Sua idade real" : "Your actual age");
    input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
    LinearLayout box = Ui.column(this);
    box.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12));
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(pt ? "Idade da conta" : "Account age")
            .setMessage(
                pt
                    ? "A conta não informou uma idade utilizável. Confirme sua idade real para"
                        + " completar o pedido de sessão. O valor fica salvo com esta conta neste"
                        + " aparelho."
                    : "The account did not provide a usable age. Confirm your actual age to"
                        + " complete the session request. The value is saved with this account on"
                        + " this device.")
            .setView(box)
            .setPositiveButton(pt ? "Continuar" : "Continue", null)
            .setNegativeButton(l.t("back"), (d, w) -> finish())
            .setOnCancelListener(d -> finish())
            .create();
    dialog.setOnDismissListener(d -> ageDialogShowing = false);
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      int age = AgePolicy.number(input.getText().toString().trim());
                      if (age < 0) {
                        input.setError(
                            pt
                                ? "Informe uma idade válida (0 a 120)."
                                : "Enter a valid age (0 to 120).");
                        return;
                      }
                      dialog.dismiss();
                      if (closed) return;
                      queue.setText(l.t("loading"));
                      network.execute(
                          () -> {
                            try {
                              api.confirmLaunchAge(age);
                              launch(recovering);
                            } catch (Exception error) {
                              error(error.getMessage());
                            }
                          });
                    }));
    dialog.show();
  }

  private void updateSeat(SessionClient.Seat value) {
    if (closed) return;
    if (failed) return;
    if (started && !playing && transportSeat != null && value.ready()
        && !transportSeat.signaling.equals(value.signaling)
        && (signaling == null || !signaling.hasOffer()) && endpointHandovers < 2) {
      endpointHandovers++;
      signalingPeer.reset();
      resetTransport();
    }
    seat = value;
    int position = queueProgress.update(value);
    if (!playing) queue.setText(position > 0 ? l.t("queue") + ": " + position
        : position == 0 ? (settings.locale().getLanguage().equals("pt")
            ? "Preparando sessão…" : "Preparing session…") : l.t("loading"));
    if (value.adsRequired) {
      error(
          "This session requires the provider’s advertising player. Complete it in the official"
              + " GeForce NOW client; this experimental player cannot complete that flow.");
      return;
    }
    if (value.ready() && !started) {
      started = true;
      startEngine(value);
    }
    if (!polling) {
      polling = true;
      ui.postDelayed(pollSeat, StreamBudget.pollDelay(playing));
    }
  }

  private final Runnable pollSeat =
      new Runnable() {
        public void run() {
          if (closed || seat == null || failed) return;
          SessionClient.Seat previous = seat;
          int generation = transportGeneration;
          network.execute(
              () -> {
                try {
                  SessionClient.Seat fresh = sessions.poll(previous);
                  pollErrors = 0;
                  ui.post(
                      () -> {
                        if (closed || generation != transportGeneration) return;
                        polling = false;
                        updateSeat(fresh);
                      });
                } catch (Exception e) {
                  pollErrors++;
                  ui.post(
                      () -> {
                        if (closed || generation != transportGeneration) return;
                        if (SessionClient.transientRead(e) && (playing || pollErrors < 4))
                          ui.postDelayed(this, Math.min(30000, 3000L * Math.min(pollErrors, 10)));
                        else error(Diagnostics.failure(e));
                      });
                }
              });
        }
      };

  private void startEngine(SessionClient.Seat seat) {
    transportSeat = seat;
    try {
      settings = settings.forSession(seat);
      videoWidth = settings.width();
      videoHeight = settings.height();
      subtitle.setText(
          settings.engine()
              + "  •  "
              + videoWidth
              + "×"
              + videoHeight
              + "  •  "
              + settings.fps()
              + " FPS  •  "
              + settings.codec());
      StreamPerformance.window(this, settings.fps());
      CrashTrace.stage(
          "inicialização do player · " + settings.engine() + "/" + settings.decoderPath());
      // Catalog artwork is released after the first decoded frame, not carried over
      // into the long-running player. Both engines share this session and account.
      StreamEngine.Events events = transportEvents(transportGeneration);
      engine =
          settings.engine().equals("native")
              ? new NativeEngine(this, settings, events)
              : new WebEngine(this, settings, events);
      root.addView(engine.view(), 0, new FrameLayout.LayoutParams(-1, -1));
      engine.view().setOnTouchListener((view, event) -> pointerTouch(event));
      offerAt = SystemClock.elapsedRealtime();
      micAtStart =
          settings.flag("mic", false)
              && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                  == PackageManager.PERMISSION_GRANTED;
      engine.start(seat, micAtStart);
      engine.audio(gameAudio);
      audio = (AudioManager) getSystemService(AUDIO_SERVICE);
      if (Build.VERSION.SDK_INT >= 26) {
        focus =
            new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setOnAudioFocusChangeListener(
                    change -> {
                      if (change == AudioManager.AUDIOFOCUS_LOSS) confirmExit();
                    })
                .build();
        audio.requestAudioFocus(focus);
      } else audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
      WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      wifi = StreamPerformance.wifi(manager, Build.VERSION.SDK_INT);
    } catch (Exception | LinkageError e) {
      error(e.getMessage());
    }
  }

  @Override
  public void ready() {
    if (closed || failed || signaling != null) return;
    CrashTrace.stage("player pronto; aguardando sinalização e vídeo · " + settings.engine());
    final int generation = transportGeneration;
    SessionClient.Seat connectionSeat = transportSeat == null ? seat : transportSeat;
    SignalingPeer.Attempt attempt = signalingPeer.next(connectionSeat.id, connectionSeat.signaling);
    signaling =
        new Signaling(
            api,
            connectionSeat,
            attempt.reconnect,
            attempt.name,
            new Signaling.Listener() {
              public void offer(String sdp) {
                if (closed || failed || generation != transportGeneration) return;
                signalingPeer.remember(connectionSeat.id, connectionSeat.signaling, signaling.peerName());
                encoder.version = Sdp.protocol(sdp);
                engine.offer(sdp);
              }

              public void ice(IceCandidate c) {
                if (!closed && !failed && generation == transportGeneration) engine.ice(c);
              }

              public void error(String m) {
                if (!closed && generation == transportGeneration) StreamActivity.this.error(m);
              }
            });
    signaling.connect();
    ui.post(heartbeat);
  }

  @Override
  public void answer(String sdp, String nvst) {
    if (!closed && signaling != null) {
      signaling.answer(sdp, nvst);
      ui.removeCallbacks(startupKeyframe);
      ui.postDelayed(startupKeyframe, 2000);
    }
  }

  @Override
  public void ice(IceCandidate c) {
    if (!closed && signaling != null) signaling.candidate(c);
  }

  @Override
  public void firstFrame() {
    ui.post(
        () -> {
          if (closed || playing) return;
          playing = true;
          CrashTrace.stage("streaming ativo · " + settings.engine() + "/" + settings.decoderPath());
          ui.removeCallbacks(watchdog);
          ui.removeCallbacks(startupKeyframe);
          root.removeView(loading);
          // Release artwork; keep the small loading controls available for recovery.
          if (loading.getChildCount() > 0 && loading.getChildAt(0) instanceof ImageView)
            ((ImageView) loading.getChildAt(0)).setImageDrawable(null);
          images.close();
          hud.setVisibility(settings.flag("stats", false) ? View.VISIBLE : View.GONE);
          scheduleController(SystemClock.elapsedRealtime(), 0);
          root.requestFocus();
          maybeCaptureMouse();
          if (physicalInput && (mouseConnected || keyboardConnected))
            Toast.makeText(this, l.t("inputDetected"), Toast.LENGTH_SHORT).show();
        });
  }

  @Override
  public void stats(JSONObject data) {
    ui.post(
        () -> {
          if (closed || !settings.flag("stats", false)) return;
          double ping = data.optDouble("ping", -1);
          hud.setText(
              String.format(
                  Locale.ROOT,
                  "%s: %d×%d · %d FPS · %s\n"
                      + "%s: %s FPS · %d×%d · %s\n"
                      + "%s: %s  ·  %s Mbps  ·  decode %s ms\n"
                      + "Dropped: %d  ·  %s",
                  l.t("streamTarget"),
                  settings.width(),
                  settings.height(),
                  settings.fps(),
                  settings.codec(),
                  l.t("streamDecoded"),
                  metric(data, "fps", 1),
                  data.optInt("width"),
                  data.optInt("height"),
                  Json.s(data, "codec"),
                  l.t("ping"),
                  ping < 0 ? "—" : Math.round(ping) + " ms",
                  metric(data, "mbps", 2),
                  metric(data, "decodeMs", 1),
                  data.optLong("dropped"),
                  Json.s(data, "decoder")));
        });
  }

  private static String metric(JSONObject data, String key, int places) {
    double value = data.optDouble(key, -1);
    return value < 0 || Double.isNaN(value) || Double.isInfinite(value)
        ? "—"
        : String.format(Locale.ROOT, "%." + places + "f", value);
  }

  @Override
  public void videoSize(int width, int height) {
    if (width > 0 && height > 0) {
      videoWidth = width;
      videoHeight = height;
    }
  }

  private final Runnable heartbeat =
      new Runnable() {
        public void run() {
          if (closed || engine == null) return;
          engine.input(InputEncoder.heartbeat());
          ui.postDelayed(this, 1000);
        }
      };
  private final Runnable controller =
      new Runnable() {
        public void run() {
          controllerPosted = false;
          controllerDue = Long.MAX_VALUE;
          if (closed || !playing) return;
          if (!controlsSuspended && toolbar.getVisibility() != View.VISIBLE) sendPad();
        }
      };
  private int startupRecoveries;
  private final Runnable startupKeyframe =
      () -> {
        if (!closed && !failed && started && !playing && signaling != null)
          signaling.requestKeyframe();
      };

  private final Runnable watchdog =
      new Runnable() {
        public void run() {
          if (closed) return;
          long now = SystemClock.elapsedRealtime();
          if (started && !playing && now - offerAt > 45000) {
            if (startupRecoveries++ == 0 && seat != null) {
              SessionClient.Seat previous = seat;
              signalingPeer.reset();
              resetTransport();
              queue.setText(
                  l.t("loading")
                      + " · "
                      + (settings.locale().getLanguage().equals("pt")
                          ? "Restabelecendo conexão"
                          : "Reconnecting"));
              network.execute(
                  () -> {
                    try {
                      SessionClient.Seat fresh = sessions.poll(previous);
                      ui.post(
                          () -> {
                            if (!closed && !failed) updateSeat(fresh);
                          });
                    } catch (Exception e) {
                      error(Diagnostics.failure(e));
                    }
                  });
              return;
            }
            error(
                "O primeiro quadro de vídeo não chegou. "
                    + (engine == null ? "Player indisponível" : engine.startupState()
                        + (signaling == null ? "" : " · " + signaling.startupState())));
            return;
          }
          ui.postDelayed(this, 1000);
        }
      };

  private void sendPad() {
    if (engine == null || closed || virtualMouse) return;
    long now = SystemClock.elapsedRealtime();
    long wait = padChanges.waitForAnalog(keyButtons | hatButtons, lt, rt, lx, ly, rx, ry, now);
    if (wait > 0 && playing && !controlsSuspended) {
      scheduleController(now, wait);
      return;
    }
    if (padChanges.accept(keyButtons | hatButtons, lt, rt, lx, ly, rx, ry, now))
      engine.input(
          encoder.gamepad(
              keyButtons | hatButtons,
              lt,
              rt,
              lx,
              ly,
              rx,
              ry,
              SystemClock.elapsedRealtimeNanos() / 1000));
    if (playing && !controlsSuspended) scheduleController(now, padChanges.nextDelay(now));
  }

  private void scheduleController(long now, long delay) {
    long due = now + Math.max(0, delay);
    if (controllerPosted && controllerDue <= due) return;
    if (controllerPosted) ui.removeCallbacks(controller);
    controllerDue = due;
    controllerPosted = true;
    if (!ui.postDelayed(controller, Math.max(0, delay))) {
      controllerPosted = false;
      controllerDue = Long.MAX_VALUE;
    }
  }

  private void cancelController() {
    ui.removeCallbacks(controller);
    controllerPosted = false;
    controllerDue = Long.MAX_VALUE;
  }

  private boolean padKey(int code, boolean down) {
    int flag = button(code);
    if (flag != 0) {
      int before = keyButtons | hatButtons;
      keyButtons = down ? keyButtons | flag : keyButtons & ~flag;
      if ((keyButtons | hatButtons) != before) sendPad();
      return true;
    }
    int value = down ? 255 : 0;
    if (code == KeyEvent.KEYCODE_BUTTON_L2) {
      if (lt != value) {
        lt = value;
        sendPad();
      }
      return true;
    }
    if (code == KeyEvent.KEYCODE_BUTTON_R2) {
      if (rt != value) {
        rt = value;
        sendPad();
      }
      return true;
    }
    return false;
  }

  private int button(int code) {
    switch (code) {
      case KeyEvent.KEYCODE_BUTTON_A:
      case KeyEvent.KEYCODE_DPAD_CENTER:
        return 0x1000;
      case KeyEvent.KEYCODE_BUTTON_B:
        return 0x2000;
      case KeyEvent.KEYCODE_BUTTON_X:
        return 0x4000;
      case KeyEvent.KEYCODE_BUTTON_Y:
        return 0x8000;
      case KeyEvent.KEYCODE_DPAD_UP:
        return 1;
      case KeyEvent.KEYCODE_DPAD_DOWN:
        return 2;
      case KeyEvent.KEYCODE_DPAD_LEFT:
        return 4;
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        return 8;
      case KeyEvent.KEYCODE_BUTTON_START:
        return 0x10;
      case KeyEvent.KEYCODE_BUTTON_SELECT:
        return 0x20;
      case KeyEvent.KEYCODE_BUTTON_THUMBL:
        return 0x40;
      case KeyEvent.KEYCODE_BUTTON_THUMBR:
        return 0x80;
      case KeyEvent.KEYCODE_BUTTON_L1:
        return 0x100;
      case KeyEvent.KEYCODE_BUTTON_R1:
        return 0x200;
      case KeyEvent.KEYCODE_BUTTON_MODE:
        return 0x400;
      default:
        return 0;
    }
  }

  private boolean gamepad(KeyEvent e) {
    int source = e.getSource(), code = e.getKeyCode();
    return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        || (code >= KeyEvent.KEYCODE_DPAD_UP
            && code <= KeyEvent.KEYCODE_DPAD_CENTER
            && padDevices.contains(e.getDeviceId()))
        || KeyEvent.isGamepadButton(code);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && playing) {
      if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) onBackPressed();
      return true;
    }
    if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      if (e.getAction() == KeyEvent.ACTION_UP) showToolbar();
      return true;
    }
    if (playing
        && !controlsSuspended
        && toolbar.getVisibility() != View.VISIBLE
        && engine != null) {
      boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
      if (!down && e.getAction() != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(e);
      boolean pad = gamepad(e);
      if (virtualMouse) {
        if (pad) {
          virtualKey(e);
          return true;
        }
        // Device lookup is only needed to distinguish typing from the virtual remote mouse.
        InputDevice device = e.getDevice();
        boolean alphabetic =
            device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
        if (!alphabetic && virtualKey(e)) return true;
      }
      if (pad && padKey(e.getKeyCode(), down)) return true;
      if (physicalInput && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
        int vk = vk(e.getKeyCode());
        if (vk > 0) {
          int mods =
              (e.isShiftPressed() ? 1 : 0)
                  | (e.isCtrlPressed() ? 2 : 0)
                  | (e.isAltPressed() ? 4 : 0);
          if (down) {
            if (!heldKeys.containsKey(vk)) {
              heldKeys.put(vk, new int[] {e.getScanCode(), mods});
              engine.input(
                  encoder.key(
                      true, vk, e.getScanCode(), mods, SystemClock.elapsedRealtimeNanos() / 1000));
            }
          } else {
            int[] pressed = heldKeys.remove(vk);
            engine.input(
                encoder.key(
                    false,
                    vk,
                    pressed == null ? e.getScanCode() : pressed[0],
                    mods,
                    SystemClock.elapsedRealtimeNanos() / 1000));
          }
          return true;
        }
      }
    }
    return super.dispatchKeyEvent(e);
  }

  static int vk(int code) {
    if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
      return 65 + code - KeyEvent.KEYCODE_A;
    if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
      return 48 + code - KeyEvent.KEYCODE_0;
    if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12)
      return 112 + code - KeyEvent.KEYCODE_F1;
    if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9)
      return 96 + code - KeyEvent.KEYCODE_NUMPAD_0;
    switch (code) {
      case KeyEvent.KEYCODE_ENTER:
      case KeyEvent.KEYCODE_NUMPAD_ENTER:
        return 13;
      case KeyEvent.KEYCODE_SPACE:
        return 32;
      case KeyEvent.KEYCODE_DEL:
        return 8;
      case KeyEvent.KEYCODE_TAB:
        return 9;
      case KeyEvent.KEYCODE_ESCAPE:
        return 27;
      case KeyEvent.KEYCODE_DPAD_UP:
        return 38;
      case KeyEvent.KEYCODE_DPAD_DOWN:
        return 40;
      case KeyEvent.KEYCODE_DPAD_LEFT:
        return 37;
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        return 39;
      case KeyEvent.KEYCODE_SHIFT_LEFT:
      case KeyEvent.KEYCODE_SHIFT_RIGHT:
        return 16;
      case KeyEvent.KEYCODE_CTRL_LEFT:
      case KeyEvent.KEYCODE_CTRL_RIGHT:
        return 17;
      case KeyEvent.KEYCODE_ALT_LEFT:
      case KeyEvent.KEYCODE_ALT_RIGHT:
        return 18;
      case KeyEvent.KEYCODE_META_LEFT:
        return 91;
      case KeyEvent.KEYCODE_META_RIGHT:
        return 92;
      case KeyEvent.KEYCODE_CAPS_LOCK:
        return 20;
      case KeyEvent.KEYCODE_PAGE_UP:
        return 33;
      case KeyEvent.KEYCODE_PAGE_DOWN:
        return 34;
      case KeyEvent.KEYCODE_MOVE_END:
        return 35;
      case KeyEvent.KEYCODE_MOVE_HOME:
        return 36;
      case KeyEvent.KEYCODE_INSERT:
        return 45;
      case KeyEvent.KEYCODE_FORWARD_DEL:
        return 46;
      case KeyEvent.KEYCODE_NUM_LOCK:
        return 144;
      case KeyEvent.KEYCODE_SCROLL_LOCK:
        return 145;
      case KeyEvent.KEYCODE_SYSRQ:
        return 44;
      case KeyEvent.KEYCODE_BREAK:
        return 19;
      case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
        return 106;
      case KeyEvent.KEYCODE_NUMPAD_ADD:
        return 107;
      case KeyEvent.KEYCODE_NUMPAD_COMMA:
        return 108;
      case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
        return 109;
      case KeyEvent.KEYCODE_NUMPAD_DOT:
        return 110;
      case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
        return 111;
      case KeyEvent.KEYCODE_SEMICOLON:
        return 186;
      case KeyEvent.KEYCODE_EQUALS:
        return 187;
      case KeyEvent.KEYCODE_COMMA:
        return 188;
      case KeyEvent.KEYCODE_MINUS:
        return 189;
      case KeyEvent.KEYCODE_PERIOD:
        return 190;
      case KeyEvent.KEYCODE_SLASH:
        return 191;
      case KeyEvent.KEYCODE_GRAVE:
        return 192;
      case KeyEvent.KEYCODE_LEFT_BRACKET:
        return 219;
      case KeyEvent.KEYCODE_BACKSLASH:
        return 220;
      case KeyEvent.KEYCODE_RIGHT_BRACKET:
        return 221;
      case KeyEvent.KEYCODE_APOSTROPHE:
        return 222;
      default:
        return 0;
    }
  }

  private float axis(MotionEvent e, int axis) {
    float v = e.getAxisValue(axis);
    return Math.abs(v) < stickDeadzone ? 0 : Math.max(-1, Math.min(1, v));
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent e) {
    if (!playing || controlsSuspended || engine == null || toolbar.getVisibility() == View.VISIBLE)
      return super.onGenericMotionEvent(e);
    if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
      if (virtualMouse) {
        virtualAxisX = axis(e, MotionEvent.AXIS_X);
        virtualAxisY = axis(e, MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X),
            hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        virtualDirections = (hy < -.5 ? 1 : hy > .5 ? 2 : 0) | (hx < -.5 ? 4 : hx > .5 ? 8 : 0);
        startVirtualTick();
        return true;
      }
      int nextLx = (int) (axis(e, MotionEvent.AXIS_X) * 32767),
          nextLy = (int) (-axis(e, MotionEvent.AXIS_Y) * 32767),
          nextRx = (int) (axis(e, MotionEvent.AXIS_Z) * 32767),
          nextRy = (int) (-axis(e, MotionEvent.AXIS_RZ) * 32767),
          nextLt =
              (int)
                  (Math.max(
                          e.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                          e.getAxisValue(MotionEvent.AXIS_BRAKE))
                      * 255),
          nextRt =
              (int)
                  (Math.max(
                          e.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                          e.getAxisValue(MotionEvent.AXIS_GAS))
                      * 255);
      float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X),
          hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
      int nextHat = (hy < -.5 ? 1 : hy > .5 ? 2 : 0) | (hx < -.5 ? 4 : hx > .5 ? 8 : 0);
      if (lx == nextLx
          && ly == nextLy
          && rx == nextRx
          && ry == nextRy
          && lt == nextLt
          && rt == nextRt
          && hatButtons == nextHat) return true;
      lx = nextLx;
      ly = nextLy;
      rx = nextRx;
      ry = nextRy;
      lt = nextLt;
      rt = nextRt;
      hatButtons = nextHat;
      sendPad();
      return true;
    }
    if ((e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        && e.getAction() == MotionEvent.ACTION_SCROLL) {
      engine.input(
          encoder.wheel(
              (int) (e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120),
              SystemClock.elapsedRealtimeNanos() / 1000));
      return true;
    }
    return super.onGenericMotionEvent(e);
  }

  public boolean onCapturedPointerEvent(MotionEvent e) {
    if (!inputAllowed() || !physicalInput || virtualMouse) return false;
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      pointerMotion.relative(e.getX(), e.getY());
      schedulePointer();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
        || e.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE)
      pointerButtons(e.getButtonState() & 7);
    if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
      flushPointerNow();
      engine.input(
          encoder.wheel(
              (int) (e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120),
              SystemClock.elapsedRealtimeNanos() / 1000));
    }
    return true;
  }

  private void createToolbar() {
    toolbar = new FrameLayout(this);
    toolbar.setBackground(Ui.bg(0xf02a2d30, Ui.dp(this, 10)));
    toolbar.setElevation(Ui.dp(this, 5));
    LinearLayout row = Ui.row(this);
    row.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
    toolbar.addView(row);
    ImageButton keyboard = toolButton("keyboard", l.t("keyboard"), this::openKeyboard);
    keyboard.setOnLongClickListener(
        v -> {
          showInputMenu(v);
          return true;
        });
    row.addView(keyboard);
    row.addView(toolButton("mouse", l.t("virtualMouse"), this::startVirtualMouse));
    ImageButton sound = toolButton("audio", l.t("gameAudio"), () -> {});
    sound.setOnClickListener(v -> showAudioMenu(v));
    row.addView(sound);
    ImageButton more = toolButton("more", l.t("streamMenu"), () -> {});
    more.setOnClickListener(v -> showStreamMenu(v));
    row.addView(more);
    row.addView(toolButton("back", l.t("backToStream"), () -> hideToolbar(true)));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    params.setMargins(Ui.dp(this, 16), Ui.dp(this, 16), 0, 0);
    root.addView(toolbar, params);
    toolbar.setVisibility(View.GONE);
  }

  private ImageButton toolButton(String icon, String label, Runnable action) {
    ImageButton button = new ImageButton(this);
    button.setImageDrawable(new StreamGlyph(icon));
    button.setContentDescription(label);
    if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    android.graphics.drawable.StateListDrawable background =
        new android.graphics.drawable.StateListDrawable();
    background.addState(
        new int[] {android.R.attr.state_focused}, Ui.bg(0xff42582d, Ui.dp(this, 6)));
    background.addState(
        new int[] {android.R.attr.state_pressed}, Ui.bg(0xff42582d, Ui.dp(this, 6)));
    background.addState(new int[0], Ui.bg(0x002a2d30, Ui.dp(this, 6)));
    button.setBackground(background);
    button.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
    button.setLayoutParams(Ui.lp(this, 44, 44));
    button.setFocusable(true);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private void showToolbar() {
    if (!playing || closed || failed) return;
    stopVirtualMouse();
    suspendControls();
    toolbar.setVisibility(View.VISIBLE);
    toolbar.getChildAt(0).requestFocus();
  }

  private void hideToolbar(boolean resume) {
    if (streamMenu != null) {
      streamMenu.dismiss();
      streamMenu = null;
    }
    toolbar.setVisibility(View.GONE);
    if (resume) resumeControls();
  }

  private void showStreamMenu(View anchor) {
    streamMenu = new PopupMenu(this, anchor);
    streamMenu
        .getMenu()
        .add(0, 1, 0, l.t("stats"))
        .setCheckable(true)
        .setChecked(settings.flag("stats", false));
    streamMenu.getMenu().add(0, 2, 1, l.t("paste"));
    streamMenu.getMenu().add(0, 3, 2, l.t("leaveGame"));
    streamMenu.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == 1) {
            boolean enabled = !settings.flag("stats", false);
            settings.set("stats", enabled);
            hud.setVisibility(enabled ? View.VISIBLE : View.GONE);
            engine.statistics(enabled);
          } else if (item.getItemId() == 2) pasteClipboard();
          else confirmExit();
          return true;
        });
    streamMenu.show();
  }

  private void showAudioMenu(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, 1, 0, l.t("gameAudio")).setCheckable(true).setChecked(gameAudio);
    menu.getMenu()
        .add(0, 2, 1, l.t("microphone"))
        .setCheckable(true)
        .setChecked(micAtStart && settings.flag("mic", false));
    menu.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == 1) {
            gameAudio = !gameAudio;
            engine.audio(gameAudio);
          } else toggleMic();
          return true;
        });
    menu.show();
  }

  private void showInputMenu(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, 1, 0, l.t("keyboardMouse")).setCheckable(true).setChecked(physicalInput);
    menu.getMenu().add(0, 2, 1, l.t("captureMouse")).setCheckable(true).setChecked(captureMouse);
    menu.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == 1) {
            releasePointerAndKeys();
            physicalInput = !physicalInput;
            settings.set("keyboardMouse", physicalInput);
          } else {
            captureMouse = !captureMouse;
            settings.set("captureMouse", captureMouse);
          }
          hideToolbar(true);
          return true;
        });
    menu.show();
  }

  private void pasteClipboard() {
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null
        || !clipboard.hasPrimaryClip()
        || clipboard.getPrimaryClip().getItemCount() == 0) return;
    CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
    if (value == null || value.length() == 0) return;
    if (value.length() > 4096) {
      Toast.makeText(this, l.t("pasteLimit"), Toast.LENGTH_LONG).show();
      return;
    }
    hideToolbar(true);
    for (byte[] packet : encoder.text(value.toString())) engine.input(packet);
  }

  private boolean inputAllowed() {
    return playing
        && !closed
        && !failed
        && !controlsSuspended
        && engine != null
        && toolbar.getVisibility() != View.VISIBLE;
  }

  private void detectDevices(boolean notify) {
    mouseConnected = keyboardConnected = false;
    padDevices.clear();
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice device = InputDevice.getDevice(id);
      if (device == null) continue;
      if (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
          || device.supportsSource(InputDevice.SOURCE_JOYSTICK)) padDevices.add(id);
      mouseConnected |= device.supportsSource(InputDevice.SOURCE_MOUSE);
      keyboardConnected |= device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }
    if (notify && playing && physicalInput && (mouseConnected || keyboardConnected))
      Toast.makeText(this, l.t("inputDetected"), Toast.LENGTH_SHORT).show();
    maybeCaptureMouse();
  }

  public void onInputDeviceAdded(int id) {
    detectDevices(true);
  }

  public void onInputDeviceChanged(int id) {
    resetDeviceInputs();
    detectDevices(false);
  }

  public void onInputDeviceRemoved(int id) {
    resetDeviceInputs();
    detectDevices(false);
  }

  private void resetDeviceInputs() {
    boolean suspended = controlsSuspended;
    suspendControls();
    stopVirtualMouse();
    controlsSuspended = suspended;
    if (!suspended && inputAllowed()) resumeControls();
  }

  private void maybeCaptureMouse() {
    if (Build.VERSION.SDK_INT >= 26
        && root != null
        && inputAllowed()
        && physicalInput
        && captureMouse
        && mouseConnected
        && !virtualMouse) {
      root.requestFocus();
      root.requestPointerCapture();
    }
  }

  private final Runnable flushPointer =
      new Runnable() {
        public void run() {
          pointerPosted = false;
          if (!inputAllowed()) {
            pointerMotion.clear();
            return;
          }
          flushPointerNow();
          if (pointerMotion.pending()) schedulePointer();
        }
      };

  private void schedulePointer() {
    if (!pointerPosted) {
      pointerPosted = true;
      ui.postDelayed(flushPointer, 16);
    }
  }

  private void flushPointerNow() {
    ui.removeCallbacks(flushPointer);
    pointerPosted = false;
    if (engine == null || closed) {
      pointerMotion.clear();
      return;
    }
    byte[] packet = pointerMotion.drain(encoder, SystemClock.elapsedRealtimeNanos() / 1000);
    if (packet != null) engine.input(packet);
  }

  private VideoViewport viewport() {
    return viewport.get(root.getWidth(), root.getHeight(), videoWidth, videoHeight);
  }

  private void positionPointer(float x, float y, boolean immediate) {
    VideoViewport v = viewport();
    pointerMotion.absolute(v.x(x), v.y(y), videoWidth, videoHeight);
    if (immediate) flushPointerNow();
    else schedulePointer();
  }

  private void pointerButtons(int next) {
    if (engine == null || closed || next == mouseButtons) return;
    flushPointerNow();
    int changes = mouseButtons ^ next;
    for (int mask : new int[] {1, 2, 4})
      if ((changes & mask) != 0)
        engine.input(
            encoder.mouseButton(
                (next & mask) != 0,
                mask == 1 ? 1 : mask == 2 ? 3 : 2,
                SystemClock.elapsedRealtimeNanos() / 1000));
    mouseButtons = next;
  }

  private void releasePointerAndKeys() {
    if (engine != null && !closed) {
      pointerButtons(0);
      for (Map.Entry<Integer, int[]> key : heldKeys.entrySet()) {
        int[] value = key.getValue();
        engine.input(
            encoder.key(
                false,
                key.getKey(),
                value[0],
                value[1],
                SystemClock.elapsedRealtimeNanos() / 1000));
      }
    }
    heldKeys.clear();
    mouseButtons = 0;
    touchPointerId = -1;
    ui.removeCallbacks(flushPointer);
    pointerPosted = false;
    pointerMotion.clear();
  }

  private boolean pointerTouch(MotionEvent e) {
    if (!inputAllowed()) return false;
    boolean mouse = (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    if (mouse && !physicalInput || !mouse && !touchInput) return false;
    int action = e.getActionMasked();
    if (mouse) {
      positionPointer(e.getX(), e.getY(), action != MotionEvent.ACTION_MOVE);
      int buttons = e.getButtonState() & 7;
      if (action == MotionEvent.ACTION_DOWN && buttons == 0) buttons = 1;
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) buttons = 0;
      pointerButtons(buttons);
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN) {
      if (!viewport().contains(e.getX(), e.getY())) return false;
      touchPointerId = e.getPointerId(0);
      positionPointer(e.getX(), e.getY(), true);
      pointerButtons(1);
    } else if (touchPointerId >= 0) {
      int index = e.findPointerIndex(touchPointerId);
      if (index >= 0 && action != MotionEvent.ACTION_CANCEL)
        positionPointer(e.getX(index), e.getY(index), action != MotionEvent.ACTION_MOVE);
      if (action == MotionEvent.ACTION_UP
          || action == MotionEvent.ACTION_CANCEL
          || action == MotionEvent.ACTION_POINTER_UP
              && e.getPointerId(e.getActionIndex()) == touchPointerId) {
        pointerButtons(0);
        touchPointerId = -1;
      }
    }
    return true;
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent e) {
    if (inputAllowed()
        && physicalInput
        && (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
      if (Build.VERSION.SDK_INT >= 26 && root.hasPointerCapture())
        return super.dispatchGenericMotionEvent(e);
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
        positionPointer(e.getX(), e.getY(), false);
        return true;
      }
      if (action == MotionEvent.ACTION_BUTTON_PRESS
          || action == MotionEvent.ACTION_BUTTON_RELEASE) {
        positionPointer(e.getX(), e.getY(), true);
        pointerButtons(e.getButtonState() & 7);
        return true;
      }
      if (action == MotionEvent.ACTION_SCROLL) {
        flushPointerNow();
        engine.input(
            encoder.wheel(
                (int) (e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120),
                SystemClock.elapsedRealtimeNanos() / 1000));
        return true;
      }
    }
    return super.dispatchGenericMotionEvent(e);
  }

  private void startVirtualMouse() {
    hideToolbar(false);
    releasePointerAndKeys();
    virtualMouse = true;
    virtualDirections = 0;
    virtualAxisX = virtualAxisY = 0;
    controlsSuspended = false;
    cursorView.setVisibility(View.VISIBLE);
    root.requestFocus();
    updateVirtualPointer();
    Toast.makeText(this, l.t("virtualMouseHint"), Toast.LENGTH_LONG).show();
  }

  private void stopVirtualMouse() {
    virtualMouse = false;
    virtualDirections = 0;
    virtualAxisX = virtualAxisY = 0;
    ui.removeCallbacks(virtualTick);
    virtualPosted = false;
    if (cursorView != null) cursorView.setVisibility(View.GONE);
    releasePointerAndKeys();
  }

  private void updateVirtualPointer() {
    VideoViewport v = viewport();
    float x = (float) (v.left + cursorX * (v.width - 1)),
        y = (float) (v.top + cursorY * (v.height - 1));
    cursorView.setTranslationX(x);
    cursorView.setTranslationY(y);
    positionPointer(x, y, true);
  }

  private void startVirtualTick() {
    if (!virtualPosted) {
      virtualPosted = true;
      virtualAt = SystemClock.elapsedRealtime() - 16;
      virtualTick.run();
    }
  }

  private final Runnable virtualTick =
      new Runnable() {
        public void run() {
          if (!virtualMouse || !inputAllowed()) {
            virtualPosted = false;
            return;
          }
          float x =
              ((virtualDirections & 8) != 0 ? 1 : 0)
                  - ((virtualDirections & 4) != 0 ? 1 : 0)
                  + virtualAxisX;
          float y =
              ((virtualDirections & 2) != 0 ? 1 : 0)
                  - ((virtualDirections & 1) != 0 ? 1 : 0)
                  + virtualAxisY;
          if (x == 0 && y == 0) {
            virtualPosted = false;
            return;
          }
          long now = SystemClock.elapsedRealtime();
          float dt = Math.max(1, Math.min(50, now - virtualAt)) / 1000f;
          virtualAt = now;
          cursorX = Math.max(0, Math.min(1, cursorX + x * .6f * dt));
          cursorY =
              Math.max(
                  0, Math.min(1, cursorY + y * .6f * dt * videoWidth / Math.max(1, videoHeight)));
          updateVirtualPointer();
          ui.postDelayed(this, 16);
        }
      };

  private boolean virtualKey(KeyEvent event) {
    int key = event.getKeyCode(),
        flag =
            key == KeyEvent.KEYCODE_DPAD_UP
                ? 1
                : key == KeyEvent.KEYCODE_DPAD_DOWN
                    ? 2
                    : key == KeyEvent.KEYCODE_DPAD_LEFT
                        ? 4
                        : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 8 : 0;
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (flag != 0) {
      if (down) virtualDirections |= flag;
      else virtualDirections &= ~flag;
      startVirtualTick();
      return true;
    }
    if (key == KeyEvent.KEYCODE_DPAD_CENTER
        || key == KeyEvent.KEYCODE_ENTER
        || key == KeyEvent.KEYCODE_BUTTON_A) {
      updateVirtualPointer();
      pointerButtons(down ? mouseButtons | 1 : mouseButtons & ~1);
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BUTTON_X) {
      updateVirtualPointer();
      pointerButtons(down ? mouseButtons | 2 : mouseButtons & ~2);
      return true;
    }
    return false;
  }

  private void toggleMic() {
    if (!micAtStart) {
      Toast.makeText(this, l.t("microphone") + " · " + l.t("settings"), Toast.LENGTH_LONG).show();
      return;
    }
    boolean enabled = !settings.flag("mic", false);
    settings.set("mic", enabled);
    if (engine != null) engine.microphone(enabled);
  }

  @Override
  public void error(String message) {
    ui.post(
        () -> {
          if (closed || failed) return;
          failed = true;
          suspendControls();
          if (keyboardDialog != null) keyboardDialog.dismiss();
          if (streamMenu != null) streamMenu.dismiss();
          ui.removeCallbacks(pollSeat);
          ui.removeCallbacks(watchdog);
          ui.removeCallbacks(startupKeyframe);
          ui.removeCallbacks(heartbeat);
          cancelController();
          new AlertDialog.Builder(this)
              .setTitle(l.t("retry"))
              .setMessage(diagnostic(message))
              .setPositiveButton(
                  l.t("retry"),
                  (d, w) -> {
                    startupRecoveries = 0;
                    endpointHandovers = 0;
                    if (!playing) signalingPeer.reset();
                    resetTransport();
                    failed = false;
                    pollErrors = 0;
                    if (seat != null) {
                      SessionClient.Seat previous = seat;
                      network.execute(
                          () -> {
                            try {
                              SessionClient.Seat fresh = sessions.poll(previous);
                              ui.post(
                                  () -> {
                                    if (!closed) updateSeat(fresh);
                                  });
                            } catch (Exception e) {
                              error(e.getMessage());
                            }
                          });
                    } else launch(true);
                  })
              .setNeutralButton(
                  "Copiar erro",
                  (d, w) -> {
                    android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("BGFN diagnostico", diagnostic(message)));
                    failed = false;
                    error(message);
                  })
              .setNegativeButton(l.t("back"), (d, w) -> finish())
              .setCancelable(false)
              .show();
        });
  }

  private String diagnostic(String message) {
    String stage = started ? "negociacao/player" : seat == null ? "criar sessao" : "fila/consulta";
    return "BGeforce Now TV "
        + BuildConfig.VERSION_NAME
        + " · "
        + stage
        + "\n"
        + settings.engine()
        + " · "
        + settings.width()
        + "x"
        + settings.height()
        + "/"
        + settings.fps()
        + " · "
        + settings.codec()
        + "\nperfil="
        + sessions.profileDescription()
        + " · appId="
        + Diagnostics.safe(getIntent().getStringExtra("variant"))
        + "\n"
        + Diagnostics.safe(message == null ? l.t("unavailable") : message);
  }

  /** Every asynchronous renderer callback belongs to one transport, never the next retry. */
  private StreamEngine.Events transportEvents(final int generation) {
    return new StreamEngine.Events() {
      private void run(Runnable action) {
        ui.post(
            () -> {
              if (!closed && !failed && generation == transportGeneration) action.run();
            });
      }

      public void ready() {
        run(StreamActivity.this::ready);
      }

      public void firstFrame() {
        run(StreamActivity.this::firstFrame);
      }

      public void stats(JSONObject value) {
        run(() -> StreamActivity.this.stats(value));
      }

      public void error(String message) {
        run(() -> StreamActivity.this.error(message));
      }

      public void answer(String sdp, String nvst) {
        run(() -> StreamActivity.this.answer(sdp, nvst));
      }

      public void ice(IceCandidate c) {
        run(() -> StreamActivity.this.ice(c));
      }

      public void keyframe() {
        run(StreamActivity.this::keyframe);
      }

      public void videoSize(int width, int height) {
        run(() -> StreamActivity.this.videoSize(width, height));
      }
    };
  }

  private void resetTransport() {
    stopVirtualMouse();
    transportGeneration++;
    padChanges.reset();
    controlsSuspended = false;
    ui.removeCallbacks(pollSeat);
    ui.removeCallbacks(heartbeat);
    cancelController();
    ui.removeCallbacks(watchdog);
    ui.removeCallbacks(startupKeyframe);
    if (signaling != null) {
      signaling.close();
      signaling = null;
    }
    if (engine != null) {
      root.removeView(engine.view());
      engine.close();
      engine = null;
    }
    if (wifi != null && wifi.isHeld()) wifi.release();
    wifi = null;
    if (audio != null) {
      if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.abandonAudioFocusRequest(focus);
      else audio.abandonAudioFocus(null);
    }
    started = false;
    playing = false;
    polling = false;
    toolbar.setVisibility(View.GONE);
    hud.setVisibility(View.GONE);
    if (loading.getParent() == null) root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    queue.setText(l.t("loading"));
    ui.postDelayed(watchdog, 1000);
  }

  @Override
  public void keyframe() {
    if (!closed && signaling != null) signaling.requestKeyframe();
  }

  private void suspendControls() {
    controlsSuspended = true;
    releasePointerAndKeys();
    cancelController();
    keyButtons = hatButtons = lt = rt = lx = ly = rx = ry = 0;
    padChanges.reset();
    if (engine != null && !closed)
      engine.input(encoder.gamepad(0, 0, 0, 0, 0, 0, 0, SystemClock.elapsedRealtimeNanos() / 1000));
    if (Build.VERSION.SDK_INT >= 26) root.releasePointerCapture();
  }

  private void resumeControls() {
    controlsSuspended = failed;
    padChanges.reset();
    if (!closed && playing && !failed) {
      root.requestFocus();
      sendPad();
      maybeCaptureMouse();
    }
  }

  private void remoteKey(int vk) {
    if (closed || engine == null || !playing) return;
    long now = SystemClock.elapsedRealtimeNanos() / 1000;
    engine.input(encoder.key(true, vk, 0, 0, now));
    engine.input(encoder.key(false, vk, 0, 0, now + 1));
  }

  private void openKeyboard() {
    if (closed
        || !playing
        || engine == null
        || (keyboardDialog != null && keyboardDialog.isShowing())) return;
    hideToolbar(false);
    suspendControls();
    boolean pt = settings.locale().getLanguage().equals("pt");
    LinearLayout panel = Ui.column(this);
    panel.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12));
    panel.addView(Ui.text(this, l.t("keyboardHint"), 13, Ui.MUTED));
    EditText text = new EditText(this);
    text.setSingleLine(true);
    text.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    text.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
    text.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(512)});
    if (Build.VERSION.SDK_INT >= 26) {
      text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
      text.setImeOptions(
          android.view.inputmethod.EditorInfo.IME_ACTION_SEND
              | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    } else text.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
    panel.addView(text, new LinearLayout.LayoutParams(-1, -2));
    CheckBox reveal = new CheckBox(this);
    reveal.setText(pt ? "Mostrar texto" : "Show text");
    reveal.setOnCheckedChangeListener(
        (v, checked) -> {
          text.setTransformationMethod(
              checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
          text.setSelection(text.length());
        });
    panel.addView(reveal);
    LinearLayout keys = Ui.row(this);
    keys.addView(Ui.button(this, "⌫", () -> remoteKey(8)));
    keys.addView(Ui.button(this, "Tab", () -> remoteKey(9)));
    keys.addView(Ui.button(this, "Esc", () -> remoteKey(27)));
    panel.addView(keys);
    keyboardDialog =
        new AlertDialog.Builder(this)
            .setTitle(l.t("keyboard"))
            .setView(panel)
            .setPositiveButton(l.t("sendText"), null)
            .setNeutralButton("Enter", null)
            .setNegativeButton(l.t("back"), null)
            .create();
    Runnable send =
        () -> {
          if (closed || engine == null || !playing) return;
          for (byte[] packet : encoder.text(text.getText().toString())) engine.input(packet);
          text.getText().clear();
        };
    text.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
              || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            send.run();
            return true;
          }
          return false;
        });
    keyboardDialog.setOnDismissListener(
        d -> {
          ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
              .hideSoftInputFromWindow(text.getWindowToken(), 0);
          text.getText().clear();
          resumeControls();
        });
    keyboardDialog.setOnShowListener(
        d -> {
          keyboardDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> send.run());
          keyboardDialog
              .getButton(AlertDialog.BUTTON_NEUTRAL)
              .setOnClickListener(
                  v -> {
                    send.run();
                    remoteKey(13);
                  });
          text.requestFocus();
          text.post(
              () ->
                  ((android.view.inputmethod.InputMethodManager)
                          getSystemService(INPUT_METHOD_SERVICE))
                      .showSoftInput(
                          text, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        });
    keyboardDialog.show();
    keyboardDialog
        .getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  private void confirmExit() {
    ui.post(
        () -> {
          if (!closed && !isFinishing()) {
            suspendControls();
            new AlertDialog.Builder(this)
                .setTitle(l.t("leaveGame"))
                .setMessage(l.t("keepSessionHint"))
                .setPositiveButton(
                    l.t("endSession"),
                    (d, w) -> {
                      endSession = true;
                      finish();
                    })
                .setNeutralButton(
                    l.t("disconnectSession"),
                    (d, w) -> {
                      endSession = false;
                      finish();
                    })
                .setNegativeButton(l.t("cancel"), null)
                .setOnDismissListener(
                    d -> {
                      if (!isFinishing() && toolbar.getVisibility() != View.VISIBLE)
                        resumeControls();
                    })
                .show();
          }
        });
  }

  @Override
  public void onBackPressed() {
    if (keyboardDialog != null && keyboardDialog.isShowing()) {
      keyboardDialog.dismiss();
      return;
    }
    if (virtualMouse) {
      stopVirtualMouse();
      resumeControls();
    } else if (toolbar.getVisibility() == View.VISIBLE) hideToolbar(true);
    else if (playing) showToolbar();
    else confirmExit();
  }

  private void stopSeat(SessionClient.Seat value) {
    try {
      sessions.stop(value);
    } catch (Exception ignored) {
      /* Server has its own session timeout if a disconnect prevented DELETE. */
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    ui.removeCallbacks(backgroundExit);
    if (playing && toolbar.getVisibility() != View.VISIBLE) resumeControls();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (playing) {
      stopVirtualMouse();
      suspendControls();
    }
    if (isFinishing()) close();
    else if (!isChangingConfigurations()) {
      // System overlays can briefly cover the player; returning cancels this close.
      ui.removeCallbacks(backgroundExit);
      ui.postDelayed(backgroundExit, 15000);
    }
  }

  private void close() {
    if (closed) return;
    stopVirtualMouse();
    closed = true;
    CrashTrace.end();
    ui.removeCallbacksAndMessages(null);
    if (signaling != null) signaling.close();
    if (engine != null) engine.close();
    images.close();
    if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
    if (wifi != null && wifi.isHeld()) wifi.release();
    if (audio != null) {
      if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.abandonAudioFocusRequest(focus);
      else audio.abandonAudioFocus(null);
    }
    SessionClient.Seat current = seat;
    if (endSession && current != null) network.execute(() -> stopSeat(current));
    network.shutdown();
  }

  @Override
  protected void onDestroy() {
    close();
    super.onDestroy();
  }
}
