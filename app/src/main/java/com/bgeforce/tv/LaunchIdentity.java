package com.bgeforce.tv;

import okhttp3.Request;

/** Allocation identity and local renderer are separate; every profile still requests WebRTC. */
enum LaunchIdentity {
  NVIDIA_BROWSER("browser", false, "BROWSER", "WEBRTC", "ANDROID", "PHONE", false),
  NVIDIA_NATIVE("windows", true, "NATIVE", "NVIDIA-CLASSIC", "WINDOWS", "DESKTOP", true),
  ALLIANCE_NATIVE("windows", true, "NATIVE", "NVIDIA-CLASSIC", "WINDOWS", "DESKTOP", true);

  static final String BROWSER_UA =
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/150.0.0.0 Mobile Safari/537.36";
  final String platform, type, streamer, os, device;
  final boolean persistSettings, desktopMonitor;

  LaunchIdentity(
      String platform,
      boolean persistSettings,
      String type,
      String streamer,
      String os,
      String device,
      boolean desktopMonitor) {
    this.platform = platform;
    this.persistSettings = persistSettings;
    this.type = type;
    this.streamer = streamer;
    this.os = os;
    this.device = device;
    this.desktopMonitor = desktopMonitor;
  }

  static LaunchIdentity select(String broker, String renderer) {
    if (!SessionClient.isNvidia(broker)) return ALLIANCE_NATIVE;
    return "native".equals(renderer) ? NVIDIA_NATIVE : NVIDIA_BROWSER;
  }

  void apply(Request.Builder request) {
    request
        .header("nv-client-type", type)
        .header("nv-client-streamer", streamer)
        .header("nv-client-version", Api.VERSION)
        .header("nv-device-os", os)
        .header("nv-device-type", device)
        .header(
            "User-Agent",
            this == ALLIANCE_NATIVE
                ? "GFN-PC/22.0 (Android 14) PGC/3.8 (6.36.38319306) okhttp/4.12.0"
                : BROWSER_UA);
  }
}
