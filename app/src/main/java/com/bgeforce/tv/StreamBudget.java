package com.bgeforce.tv;

/** Requested stream budget; server negotiation and device throughput still determine the result. */
final class StreamBudget {
  static long pollDelay(boolean playing) {
    // Queue position stays responsive; active media has its own unchanged heartbeats.
    return playing ? 10000 : 3000;
  }

  static boolean constrained(boolean lowRam, long totalBytes) {
    return lowRam || (totalBytes > 0 && totalBytes <= 1536L * 1024 * 1024);
  }

  static String effective(String selected, boolean constrained) {
    if ("auto".equals(selected) || "balanced".equals(selected)) return "balanced";
    return "light".equals(selected) || "ultra".equals(selected) || "fullhd".equals(selected)
        ? selected
        : "custom";
  }

  static int width(String mode, int custom) {
    return "fullhd".equals(mode)
        ? 1920
        : "ultra".equals(mode) ? 854 : "custom".equals(mode) ? custom : 1280;
  }

  static int fps(String mode, int custom) {
    return "custom".equals(mode)
        ? custom
        : ("balanced".equals(mode) || "fullhd".equals(mode)) ? 60 : 30;
  }

  static int bitrate(String mode, int custom) {
    return "fullhd".equals(mode)
        ? 15
        : "ultra".equals(mode)
            ? 3
            : "light".equals(mode) ? 4 : "balanced".equals(mode) ? 8 : custom;
  }

  static String codec(String mode, String custom) {
    return "custom".equals(mode) ? custom : "H264";
  }
}
