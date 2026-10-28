package com.bgeforce.tv;

import android.app.Activity;
import android.os.*;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

public final class NativeEngine implements StreamEngine {
  private static boolean initialized;
  private final Activity activity;
  private final Settings settings;
  private final Events events;
  private final SurfaceViewRenderer renderer;
  private final SurfaceView direct;
  private final FrameLayout output;
  private final java.util.concurrent.atomic.AtomicBoolean textureStarted =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile boolean textureReady;
  private final VideoSink videoSink;
  private final EglBase egl = EglBase.create();
  private final ScheduledExecutorService rtc = Executors.newSingleThreadScheduledExecutor();
  private final Handler ui = new Handler(Looper.getMainLooper());
  private PeerConnectionFactory factory;
  private PeerConnection peer;
  private JavaAudioDeviceModule audio;
  private AudioSource micSource;
  private AudioTrack micTrack;
  private final List<AudioTrack> remoteAudio = new ArrayList<>();
  private boolean audioEnabled = true;
  private VideoTrack videoTrack;
  private DataChannel input;
  private final List<DataChannel> channels = new ArrayList<>();
  private final List<IceCandidate> candidates = new ArrayList<>();
  private volatile boolean closed, remoteReady;
  private boolean microphone;
  private final StreamMetrics metrics = new StreamMetrics();
  private final java.util.concurrent.atomic.AtomicInteger statsGeneration =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile boolean statisticsEnabled;
  private final java.util.concurrent.atomic.AtomicBoolean samplePending =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile String startupIce = "NEW";
  private volatile boolean startupOffer, startupAnswer, startupTrack;
  private volatile int startupCandidates;
  private String codecName = "";
  private int outputWidth, outputHeight;
  private SessionClient.Seat seat;
  private final ReliableInputs inputQueue = new ReliableInputs();
  private final java.util.concurrent.atomic.AtomicBoolean inputScheduled =
      new java.util.concurrent.atomic.AtomicBoolean();

  public NativeEngine(Activity a, Settings settings, Events events) {
    activity = a;
    this.settings = settings;
    this.events = events;
    synchronized (NativeEngine.class) {
      if (!initialized) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(a.getApplicationContext())
                .setEnableInternalTracer(false)
                .createInitializationOptions());
        initialized = true;
      }
    }
    renderer = new SurfaceViewRenderer(a);
    StreamPerformance.surface(renderer, settings.fps());
    videoSink =
        frame -> {
          if (closed) return;
          if (frame.getBuffer() instanceof SurfaceFrame) {
            if (outputWidth != frame.getRotatedWidth()
                || outputHeight != frame.getRotatedHeight()) {
              outputWidth = frame.getRotatedWidth();
              outputHeight = frame.getRotatedHeight();
              events.videoSize(outputWidth, outputHeight);
            }
            ((SurfaceFrame) frame.getBuffer()).present();
          } else {
            if (!textureReady) textureOutput();
            if (textureReady) renderer.onFrame(frame);
          }
        };
    output = new FrameLayout(a);
    output.setBackgroundColor(android.graphics.Color.BLACK);
    if (settings.directSurface()) {
      direct = new SurfaceView(a);
      StreamPerformance.surface(direct, settings.fps());
      direct.getHolder().setFixedSize(settings.width(), settings.height());
      output.addView(direct, new FrameLayout.LayoutParams(-1, -1));
    } else {
      direct = null;
      textureOutput();
    }
  }

  private void textureOutput() {
    if (!textureStarted.compareAndSet(false, true)) return;
    Runnable configure =
        () -> {
          if (closed) return;
          try {
            output.removeAllViews();
            renderer.init(
                egl.getEglBaseContext(),
                new RendererCommon.RendererEvents() {
                  public void onFirstFrameRendered() {
                    ui.post(events::firstFrame);
                  }

                  public void onFrameResolutionChanged(int width, int height, int rotation) {
                    events.videoSize(
                        rotation % 180 == 0 ? width : height, rotation % 180 == 0 ? height : width);
                  }
                });
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            renderer.setEnableHardwareScaler(true);
            renderer.setMirror(false);
            output.addView(renderer, new FrameLayout.LayoutParams(-1, -1));
            textureReady = true;
            events.keyframe();
          } catch (Exception | LinkageError error) {
            events.error("Video output initialization: " + error.getClass().getSimpleName());
          }
        };
    if (Looper.myLooper() == Looper.getMainLooper()) configure.run();
    else ui.post(configure);
  }

  @Override
  public String startupState() {
    return "SDP="
        + (startupOffer ? "recebido" : "aguardando")
        + " · resposta="
        + startupAnswer
        + " · ICE="
        + startupIce
        + " · candidatos="
        + startupCandidates
        + " · vídeo="
        + startupTrack;
  }

  @Override
  public View view() {
    return output;
  }

  private void execute(Runnable r) {
    if (!closed)
      try {
        rtc.execute(
            () -> {
              if (!closed)
                try {
                  r.run();
                } catch (Exception | LinkageError e) {
                  events.error(
                      e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            });
      } catch (RejectedExecutionException ignored) {
      }
  }

  @Override
  public void start(SessionClient.Seat seat, boolean mic) {
    this.seat = seat;
    microphone = mic;
    execute(
        () -> {
          DecoderFactory decoders =
              new DecoderFactory(
                  egl.getEglBaseContext(),
                  settings,
                  () -> direct == null ? null : direct.getHolder().getSurface(),
                  this::textureOutput,
                  () -> ui.post(events::firstFrame));
          if (decoders.getSupportedCodecs().length == 0) {
            events.error(
                "No hardware WebRTC decoder for "
                    + settings.codec()
                    + " "
                    + settings.width()
                    + "x"
                    + settings.height()
                    + " @ "
                    + settings.fps());
            return;
          }
          audio =
              JavaAudioDeviceModule.builder(activity.getApplicationContext())
                  .setUseStereoOutput(true)
                  .setUseStereoInput(false)
                  .setUseHardwareAcousticEchoCanceler(true)
                  .setUseHardwareNoiseSuppressor(true)
                  .setUseLowLatency(true)
                  .setEnableVolumeLogger(false)
                  .createAudioDeviceModule();
          factory =
              PeerConnectionFactory.builder()
                  .setAudioDeviceModule(audio)
                  .setVideoDecoderFactory(decoders)
                  .setVideoEncoderFactory(
                      new DefaultVideoEncoderFactory(egl.getEglBaseContext(), false, false))
                  .createPeerConnectionFactory();
          List<PeerConnection.IceServer> servers = new ArrayList<>();
          for (int i = 0; i < seat.ice.length(); i++) {
            JSONObject s = seat.ice.optJSONObject(i);
            if (s == null) continue;
            List<String> urls = new ArrayList<>();
            Object raw = s.opt("urls");
            if (raw instanceof JSONArray) {
              JSONArray aa = (JSONArray) raw;
              for (int j = 0; j < aa.length(); j++) urls.add(aa.optString(j));
            } else if (raw instanceof String) urls.add((String) raw);
            if (!urls.isEmpty())
              servers.add(
                  PeerConnection.IceServer.builder(urls)
                      .setUsername(Json.s(s, "username"))
                      .setPassword(Json.s(s, "credential"))
                      .createIceServer());
          }
          if (servers.isEmpty())
            servers.add(
                PeerConnection.IceServer.builder("stun:s1.stun.gamestream.nvidia.com:19308")
                    .createIceServer());
          PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
          config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
          config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
          config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
          config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
          config.enableCpuOveruseDetection = false;
          config.audioJitterBufferMaxPackets = 50;
          config.audioJitterBufferFastAccelerate = true;
          peer =
              factory.createPeerConnection(
                  config,
                  new PeerConnection.Observer() {
                    public void onSignalingChange(PeerConnection.SignalingState s) {}

                    public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                      startupIce = s.name();
                      if (s == PeerConnection.IceConnectionState.FAILED)
                        events.error("ICE failed; check the network or try another server");
                    }

                    public void onIceConnectionReceivingChange(boolean r) {}

                    public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}

                    public void onIceCandidate(IceCandidate c) {
                      if (!closed) events.ice(c);
                    }

                    public void onIceCandidatesRemoved(IceCandidate[] c) {}

                    public void onAddStream(MediaStream s) {
                      for (VideoTrack v : s.videoTracks) attach(v);
                      for (AudioTrack t : s.audioTracks) execute(() -> attachAudio(t));
                    }

                    public void onRemoveStream(MediaStream s) {}

                    public void onDataChannel(DataChannel c) {
                      execute(() -> channel(c));
                    }

                    public void onRenegotiationNeeded() {}

                    public void onAddTrack(RtpReceiver r, MediaStream[] s) {
                      track(r.track());
                    }

                    public void onTrack(RtpTransceiver t) {
                      track(t.getReceiver().track());
                    }
                  });
          if (peer == null) throw new IllegalStateException("PeerConnection initialization failed");
          ui.post(events::ready);
          statistics(settings.flag("stats", false));
        });
  }

  private void track(MediaStreamTrack t) {
    if (t instanceof VideoTrack) execute(() -> attach((VideoTrack) t));
    else if (t instanceof AudioTrack) execute(() -> attachAudio((AudioTrack) t));
  }

  private void attachAudio(AudioTrack track) {
    if (!remoteAudio.contains(track)) remoteAudio.add(track);
    track.setEnabled(audioEnabled);
  }

  @Override
  public void audio(boolean enabled) {
    execute(
        () -> {
          audioEnabled = enabled;
          for (AudioTrack track : remoteAudio) track.setEnabled(enabled);
        });
  }

  private void attach(VideoTrack v) {
    if (closed || videoTrack == v) return;
    if (videoTrack != null) videoTrack.removeSink(videoSink);
    startupTrack = true;
    videoTrack = v;
    v.setEnabled(true);
    v.addSink(videoSink);
  }

  private void channel(DataChannel c) {
    if (c == null) return;
    channels.add(c);
    if (c.label().equals("input_channel_v1")) input = c;
  }

  @Override
  public void offer(String offer) {
    startupOffer = true;
    execute(
        () -> {
          String chosen =
              Sdp.prefer(
                  Sdp.serverEndpoint(
                      offer, seat.mediaIp.isEmpty() ? seat.ip : seat.mediaIp, seat.mediaPort),
                  settings.codec());
          DataChannel.Init reliable = new DataChannel.Init();
          reliable.ordered = true;
          channel(peer.createDataChannel("input_channel_v1", reliable));
          DataChannel.Init partial = new DataChannel.Init();
          partial.ordered = false;
          partial.maxRetransmitTimeMs = 30;
          channel(peer.createDataChannel("input_channel_partially_reliable", partial));
          DataChannel.Init stats = new DataChannel.Init();
          stats.ordered = false;
          stats.maxRetransmits = 0;
          channel(peer.createDataChannel("stats_channel", stats));
          peer.setRemoteDescription(
              new Observer() {
                @Override
                public void onSetSuccess() {
                  execute(
                      () -> {
                        remoteReady = true;
                        for (IceCandidate c : candidates) peer.addIceCandidate(c);
                        candidates.clear();
                        if (microphone) attachMicrophone();
                        peer.createAnswer(
                            new Observer() {
                              @Override
                              public void onCreateSuccess(SessionDescription d) {
                                execute(
                                    () -> {
                                      String answer = Sdp.answer(d.description, settings.kbps());
                                      peer.setLocalDescription(
                                          new Observer() {
                                            @Override
                                            public void onSetSuccess() {
                                              startupAnswer = true;
                                              if (!closed)
                                                events.answer(
                                                    answer,
                                                    Sdp.nvst(
                                                        chosen,
                                                        answer,
                                                        settings.width(),
                                                        settings.height(),
                                                        settings.fps(),
                                                        settings.kbps(),
                                                        settings.flag("adaptive", false),
                                                        settings.referenceFrames()));
                                            }
                                          },
                                          new SessionDescription(
                                              SessionDescription.Type.ANSWER, answer));
                                    });
                              }
                            },
                            new MediaConstraints());
                      });
                }
              },
              new SessionDescription(SessionDescription.Type.OFFER, chosen));
        });
  }

  @Override
  public void ice(IceCandidate c) {
    startupCandidates++;
    execute(
        () -> {
          String fixed =
              Sdp.serverEndpoint(
                      "a=" + c.sdp, seat.mediaIp.isEmpty() ? seat.ip : seat.mediaIp, seat.mediaPort)
                  .substring(2);
          IceCandidate candidate = new IceCandidate(c.sdpMid, c.sdpMLineIndex, fixed);
          if (remoteReady) peer.addIceCandidate(candidate);
          else if (candidates.size() < 128) candidates.add(candidate);
        });
  }

  @Override
  public void input(byte[] packet) {
    if (closed) return;
    if (!inputQueue.offer(packet)) {
      events.error("Control input queue is full");
      return;
    }
    if (inputScheduled.compareAndSet(false, true)) execute(this::drainInputs);
  }

  private void drainInputs() {
    if (closed) {
      inputQueue.clear();
      inputScheduled.set(false);
      return;
    }
    if (input != null && input.state() == DataChannel.State.OPEN) {
      while (input.bufferedAmount() < 32768) {
        byte[] packet = inputQueue.poll();
        if (packet == null) break;
        if (!input.send(new DataChannel.Buffer(ByteBuffer.wrap(packet), true))) {
          events.error("Control data channel rejected a packet");
          break;
        }
      }
    }
    if (inputQueue.isEmpty()) {
      inputScheduled.set(false);
      if (!inputQueue.isEmpty() && inputScheduled.compareAndSet(false, true))
        execute(this::drainInputs);
    } else
      try {
        rtc.schedule(this::drainInputs, 50, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException ignored) {
        inputQueue.clear();
      }
  }

  @Override
  public void statistics(boolean enabled) {
    statsGeneration.incrementAndGet();
    statisticsEnabled = enabled;
    metrics.reset();
    ui.post(
        () -> {
          ui.removeCallbacks(statsLoop);
          if (!closed && enabled) ui.post(statsLoop);
        });
  }

  @Override
  public void microphone(boolean enabled) {
    execute(
        () -> {
          microphone = enabled;
          if (micTrack == null && enabled) {
            events.error("Enable the microphone before starting a new session");
            return;
          }
          if (micTrack != null) micTrack.setEnabled(enabled);
          if (audio != null) audio.setMicrophoneMute(!enabled);
        });
  }

  private void attachMicrophone() {
    micSource = factory.createAudioSource(new MediaConstraints());
    micTrack = factory.createAudioTrack("bgfn-mic", micSource);
    List<RtpTransceiver> list = peer.getTransceivers();
    RtpTransceiver choice = null;
    for (RtpTransceiver t : list)
      if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
          && "3".equals(t.getMid())) {
        choice = t;
        break;
      }
    if (choice == null)
      for (RtpTransceiver t : list)
        if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            && t.getDirection() == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) {
          choice = t;
          break;
        }
    if (choice == null) {
      peer.addTrack(micTrack, Collections.singletonList("mic"));
    } else {
      if (choice.getDirection() == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        choice.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
      else if (choice.getDirection() == RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        choice.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
      choice.getSender().setStreams(Collections.singletonList("mic"));
      if (!choice.getSender().setTrack(micTrack, false))
        throw new IllegalStateException("Could not attach microphone");
    }
    micTrack.setEnabled(true);
  }

  private final Runnable statsLoop =
      new Runnable() {
        public void run() {
          if (closed || !statisticsEnabled) return;
          if (!samplePending.compareAndSet(false, true)) {
            ui.postDelayed(this, 2000);
            return;
          }
          final int generation = statsGeneration.get();
          execute(() -> sampleStats(generation));
          ui.postDelayed(this, 2000);
        }
      };

  private void sampleStats(int generation) {
    try {
      if (peer == null) {
        samplePending.set(false);
        return;
      }
      peer.getStats(
          report -> {
            try {
              if (closed || !statisticsEnabled || generation != statsGeneration.get()) return;
              RTCStats video = null;
              Map<String, Object> pair = null;
              Map<String, RTCStats> all = report.getStatsMap();
              for (RTCStats st : all.values()) {
                Map<String, Object> m = st.getMembers();
                if (st.getType().equals("inbound-rtp") && "video".equals(m.get("kind"))) video = st;
                if (st.getType().equals("candidate-pair")
                    && Boolean.TRUE.equals(m.get("nominated"))
                    && "succeeded".equals(m.get("state"))) pair = m;
              }
              if (video == null) return;
              Map<String, Object> data = video.getMembers();
              RTCStats codec = all.get(String.valueOf(data.get("codecId")));
              if (codec != null) codecName = String.valueOf(codec.getMembers().get("mimeType"));
              events.stats(
                  metrics.sample(
                      video.getId(), data, pair, codecName, SystemClock.elapsedRealtime()));
            } catch (RuntimeException | LinkageError ignored) {
              // An optional HUD sample must never end a valid media session/JNI callback.
            } finally {
              samplePending.set(false);
            }
          });
    } catch (RuntimeException | LinkageError ignored) {
      samplePending.set(false);
    }
  }

  private class Observer implements SdpObserver {
    public void onCreateSuccess(SessionDescription d) {}

    public void onSetSuccess() {}

    public void onCreateFailure(String e) {
      if (!closed) events.error("SDP: " + e);
    }

    public void onSetFailure(String e) {
      if (!closed) events.error("SDP: " + e);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ui.removeCallbacks(statsLoop);
    inputQueue.clear();
    rtc.execute(
        () -> {
          if (videoTrack != null) videoTrack.removeSink(videoSink);
          remoteAudio.clear();
          for (DataChannel c : channels) {
            c.close();
            c.dispose();
          }
          channels.clear();
          if (peer != null) {
            peer.close();
            peer.dispose();
            peer = null;
          }
          if (micTrack != null) micTrack.dispose();
          if (micSource != null) micSource.dispose();
          if (factory != null) factory.dispose();
          if (audio != null) audio.release();
          ui.post(
              () -> {
                if (textureStarted.get()) renderer.release();
                egl.release();
              });
        });
    rtc.shutdown();
  }
}
