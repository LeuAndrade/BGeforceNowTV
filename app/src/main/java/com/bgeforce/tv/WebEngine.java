package com.bgeforce.tv;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.*;
import java.io.*;
import org.json.*;
import org.webrtc.IceCandidate;

/** The fallback loads our local RTC player, never an authenticated remote page. */
public final class WebEngine implements StreamEngine {
  static final String ORIGIN = "https://bgef.local";
  private final Activity activity;
  private final Settings settings;
  private final Events events;
  private final WebView web;
  private volatile boolean closed;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ReliableInputs bridgeInputs = new ReliableInputs();
  private final java.util.concurrent.atomic.AtomicBoolean inputPosted =
      new java.util.concurrent.atomic.AtomicBoolean();
  private String currentOffer = "";
  private SessionClient.Seat seat;
  private boolean mic, pageReady;
  private boolean statisticsEnabled;

  public WebEngine(Activity activity, Settings settings, Events events) {
    this.activity = activity;
    this.settings = settings;
    this.events = events;
    statisticsEnabled = settings.flag("stats", false);
    web = new WebView(activity);
    WebSettings w = web.getSettings();
    w.setJavaScriptEnabled(true);
    w.setAllowFileAccess(false);
    w.setAllowContentAccess(false);
    w.setDomStorageEnabled(false);
    w.setMediaPlaybackRequiresUserGesture(false);
    w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    w.setCacheMode(WebSettings.LOAD_NO_CACHE);
    web.addJavascriptInterface(new Bridge(), "BGFN");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return true;
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (ORIGIN.equals(u.getScheme() + "://" + u.getHost())
                && "/stream.html".equals(u.getPath()))
              try {
                return new WebResourceResponse(
                    "text/html", "UTF-8", activity.getAssets().open("stream.html"));
              } catch (IOException e) {
                return denied();
              }
            return denied();
          }

          @Override
          public void onPageFinished(WebView v, String url) {
            if (!closed && url.equals(ORIGIN + "/stream.html")) {
              pageReady = true;
              js(
                  "init("
                      + Json.o(
                              "iceServers",
                              seat.ice,
                              "microphone",
                              mic,
                              "codec",
                              settings.codec(),
                              "statistics",
                              statisticsEnabled)
                          .toString()
                      + ")");
            }
          }
        });
    web.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(PermissionRequest r) {
            activity.runOnUiThread(
                () -> {
                  if (closed
                      || !ORIGIN.equals(r.getOrigin().toString().replaceAll("/+$", ""))
                      || !mic
                      || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                          != PackageManager.PERMISSION_GRANTED) {
                    r.deny();
                    return;
                  }
                  for (String resource : r.getResources())
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                      r.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                      return;
                    }
                  r.deny();
                });
          }
        });
  }

  private static WebResourceResponse denied() {
    return new WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        java.util.Collections.emptyMap(),
        new ByteArrayInputStream(new byte[0]));
  }

  private void js(String code) {
    activity.runOnUiThread(
        () -> {
          if (!closed) web.evaluateJavascript(code, null);
        });
  }

  @Override
  public View view() {
    return web;
  }

  @Override
  public void start(SessionClient.Seat seat, boolean microphone) {
    this.seat = seat;
    mic = microphone;
    web.loadUrl(ORIGIN + "/stream.html");
  }

  @Override
  public void offer(String sdp) {
    try {
      currentOffer =
          Sdp.prefer(
              Sdp.serverEndpoint(
                  sdp, seat.mediaIp.isEmpty() ? seat.ip : seat.mediaIp, seat.mediaPort),
              settings.codec());
      js("offer(" + JSONObject.quote(currentOffer) + "," + settings.kbps() + ")");
    } catch (Exception e) {
      events.error(e.getMessage());
    }
  }

  @Override
  public void ice(IceCandidate c) {
    String fixed =
        Sdp.serverEndpoint(
                "a=" + c.sdp, seat.mediaIp.isEmpty() ? seat.ip : seat.mediaIp, seat.mediaPort)
            .substring(2);
    js(
        "ice("
            + Json.o("candidate", fixed, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex)
            + ")");
  }

  @Override
  public void input(byte[] packet) {
    if (closed) return;
    if (!bridgeInputs.offer(packet)) {
      events.error("Control input queue is full");
      return;
    }
    if (inputPosted.compareAndSet(false, true)) ui.post(flushInputs);
  }

  // One JavaScript crossing per UI turn, preserving every key-down/key-up/text packet.
  private final Runnable flushInputs = this::flushInputs;

  private void flushInputs() {
    if (closed) return;
    StringBuilder script = new StringBuilder("inputs([");
    byte[] packet;
    boolean first = true;
    // A bound also prevents a concurrent producer from monopolizing the UI thread.
    for (int count = 0; count < 128 && (packet = bridgeInputs.poll()) != null; count++) {
      if (!first) script.append(',');
      first = false;
      script
          .append('"')
          .append(android.util.Base64.encodeToString(packet, android.util.Base64.NO_WRAP))
          .append('"');
    }
    if (!first) js(script.append("])").toString());
    inputPosted.set(false);
    if (!bridgeInputs.isEmpty() && inputPosted.compareAndSet(false, true)) ui.post(flushInputs);
  }

  @Override
  public void microphone(boolean enabled) {
    mic = enabled;
    js("mute(" + (!enabled) + ")");
  }

  @Override
  public void statistics(boolean enabled) {
    statisticsEnabled = enabled;
    if (pageReady) js("statistics(" + enabled + ")");
  }

  @Override
  public void audio(boolean enabled) {
    js("audio(" + enabled + ")");
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ui.removeCallbacks(flushInputs);
    bridgeInputs.clear();
    web.removeJavascriptInterface("BGFN");
    web.stopLoading();
    web.loadUrl("about:blank");
    web.destroy();
  }

  public final class Bridge {
    @JavascriptInterface
    public void videoSize(int width, int height) {
      if (!closed && width > 0 && height > 0)
        activity.runOnUiThread(() -> events.videoSize(width, height));
    }

    @JavascriptInterface
    public void ready() {
      if (!closed) activity.runOnUiThread(events::ready);
    }

    @JavascriptInterface
    public void firstFrame() {
      if (!closed) activity.runOnUiThread(events::firstFrame);
    }

    @JavascriptInterface
    public void answer(String sdp) {
      if (!closed)
        events.answer(
            sdp,
            Sdp.nvst(
                currentOffer,
                sdp,
                settings.width(),
                settings.height(),
                settings.fps(),
                settings.kbps(),
                settings.flag("adaptive", false),
                settings.referenceFrames()));
    }

    @JavascriptInterface
    public void candidate(String json) {
      if (!closed) {
        JSONObject c = Json.parse(json);
        events.ice(
            new IceCandidate(
                Json.s(c, "sdpMid"), c.optInt("sdpMLineIndex"), Json.s(c, "candidate")));
      }
    }

    @JavascriptInterface
    public void stats(String json) {
      if (!closed) events.stats(Json.parse(json));
    }

    @JavascriptInterface
    public void error(String message) {
      if (!closed) events.error(message);
    }
  }
}
