package com.bgeforce.tv;

import java.util.*;
import java.util.regex.*;

/** GFN has a normal WebRTC SDP plus an NVST settings document. */
public final class Sdp {
  private Sdp() {}

  static String attribute(String s, String name, String fallback) {
    Matcher m = Pattern.compile("(?m)^a=" + Pattern.quote(name) + ":([^\\r\\n]+)").matcher(s);
    return m.find() ? m.group(1).trim() : fallback;
  }

  public static int protocol(String s) {
    try {
      return Integer.parseInt(attribute(s, "ri.version", "3"));
    } catch (NumberFormatException e) {
      return 3;
    }
  }

  public static String serverEndpoint(String s, String ip, int port) {
    if (ip == null || !ip.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return s;
    Pattern p =
        Pattern.compile("(a=candidate:\\S+\\s+\\d+\\s+\\w+\\s+\\d+\\s+)([^\\s]+)\\s+(\\d+)");
    Matcher m = p.matcher(s);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String host = m.group(2);
      int end = s.indexOf('\n', m.end());
      String suffix =
          s.substring(m.end(), end < 0 ? s.length() : end).toLowerCase(Locale.ROOT).trim();
      boolean hostCandidate = suffix.matches(".*\\btyp\\s+host\\b.*");
      boolean relayCandidate = suffix.matches(".*\\btyp\\s+relay\\b.*");
      if (!relayCandidate
          && (privateIp(host)
              || (hostCandidate
                  && port > 0
                  && m.group(1).toLowerCase(Locale.ROOT).contains(" udp "))))
        m.appendReplacement(
            out, Matcher.quoteReplacement(m.group(1) + ip + " " + (port > 0 ? port : m.group(3))));
    }
    m.appendTail(out);
    return out.toString()
        .replaceAll(
            "(?m)^c=IN IP4 (?:10\\.[^\\r"
                + "\\n"
                + "]+|192\\.168\\.[^\\r"
                + "\\n"
                + "]+|172\\.(?:1[6-9]|2[0-9]|3[01])\\.[^\\r"
                + "\\n"
                + "]+|0\\.0\\.0\\.0)",
            "c=IN IP4 " + ip);
  }

  private static boolean privateIp(String ip) {
    return ip.equals("0.0.0.0")
        || ip.startsWith("10.")
        || ip.startsWith("192.168.")
        || ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
  }

  public static String prefer(String s, String codec) {
    String target = codec.equals("H265") ? "(?:H265|HEVC)" : Pattern.quote(codec);
    Set<String> pts = new LinkedHashSet<>();
    Map<String, String> rtx = new HashMap<>();
    boolean video = false;
    for (String line : s.split("\\r?\\n")) {
      if (line.startsWith("m=")) video = line.startsWith("m=video");
      if (!video) continue;
      Matcher m =
          Pattern.compile("^a=rtpmap:(\\d+) " + target + "/", Pattern.CASE_INSENSITIVE)
              .matcher(line);
      if (m.find()) pts.add(m.group(1));
      Matcher r = Pattern.compile("^a=fmtp:(\\d+) .*?apt=(\\d+)").matcher(line);
      if (r.find()) rtx.put(r.group(1), r.group(2));
    }
    if (pts.isEmpty()) throw new IllegalArgumentException("The server did not offer " + codec);
    Set<String> allowed = new LinkedHashSet<>(pts);
    for (Map.Entry<String, String> r : rtx.entrySet())
      if (pts.contains(r.getValue())) allowed.add(r.getKey());
    StringBuilder out = new StringBuilder();
    video = false;
    for (String line : s.split("\\r?\\n")) {
      if (line.startsWith("m=")) video = line.startsWith("m=video");
      if (video && line.startsWith("m=video")) {
        String[] p = line.split(" +");
        line = p[0] + " " + p[1] + " " + p[2] + " " + Json.join(" ", allowed);
      } else if (video
          && (line.startsWith("a=rtpmap:")
              || line.startsWith("a=fmtp:")
              || line.startsWith("a=rtcp-fb:"))) {
        String pt = line.substring(line.indexOf(':') + 1).split(" ")[0];
        if (!pt.equals("*") && !allowed.contains(pt)) continue;
      }
      if (!line.isEmpty()) out.append(line).append("\r\n");
    }
    return out.toString();
  }

  public static String answer(String s, int kbps) {
    List<String> out = new ArrayList<>();
    for (String line : s.split("\\r?\\n")) {
      if (line.startsWith("a=fmtp:") && line.contains("minptime=") && !line.contains("stereo="))
        line += ";stereo=1";
      if (line.isEmpty()) continue;
      out.add(line);
      if (line.startsWith("m=video")) out.add("b=AS:" + kbps);
      if (line.startsWith("m=audio")) out.add("b=AS:128");
    }
    return Json.join("\r\n", out) + "\r\n";
  }

  public static String nvst(
      String offer, String answer, int width, int height, int fps, int kbps, boolean adapt) {
    return nvst(offer, answer, width, height, fps, kbps, adapt, 4);
  }

  public static String nvst(
      String offer,
      String answer,
      int width,
      int height,
      int fps,
      int kbps,
      boolean adapt,
      int referenceFrames) {
    List<String> lines =
        new ArrayList<>(
            Arrays.asList(
                "v=0",
                "o=SdpTest test_id_13 14 IN IPv4 127.0.0.1",
                "s=-",
                "t=0 0",
                "a=general.icePassword:" + attribute(answer, "ice-pwd", ""),
                "a=general.iceUserNameFragment:" + attribute(answer, "ice-ufrag", ""),
                "a=general.dtlsFingerprint:"
                    + attribute(answer, "fingerprint", "sha-256 ").replaceFirst("^sha-256 ", ""),
                "m=video 0 RTP/AVP",
                "a=msid:fbc-video-0",
                "a=vqos.fec.rateDropWindow:10",
                "a=vqos.fec.minRequiredFecPackets:2",
                "a=vqos.fec.repairMinPercent:5",
                "a=vqos.fec.repairPercent:5",
                "a=vqos.fec.repairMaxPercent:35",
                "a=vqos.bllFec.enable:0",
                "a=vqos.dynamicStreamingMode:" + (adapt ? 1 : 0),
                "a=vqos.drc.enable:" + (adapt ? 1 : 0),
                "a=vqos.dfc.enable:0",
                "a=vqos.dfc.adjustResAndFps:0",
                "a=vqos.calculateAvgVideoStreamingBitrate:1",
                "a=video.dx9EnableNv12:1",
                "a=video.dx9EnableHdr:0",
                "a=video.framePacing.mode:2",
                "a=bwe.useOwdCongestionControl:1",
                "a=video.enableRtpNack:1",
                "a=video.packetSize:1140",
                "a=packetPacing.version:3",
                "a=packetPacing.mode:1",
                "a=packetPacing.minNumPacketsPerGroup:15",
                "a=packetPacing.enableAccurateSleep:1",
                "a=packetPacing.enableSmoothTransition:1",
                "a=video.rtpNackQueueLength:1024",
                "a=video.rtpNackQueueMaxPackets:512",
                "a=video.rtpNackMaxPacketCount:25",
                "a=video.clientViewportWd:" + width,
                "a=video.clientViewportHt:" + height,
                "a=video.maxFPS:" + fps,
                "a=video.initialBitrateKbps:" + Math.min(kbps, Math.max(1500, kbps / 2)),
                "a=video.initialPeakBitrateKbps:" + Math.min(kbps, Math.max(1500, kbps / 2)),
                "a=vqos.bw.maximumBitrateKbps:" + kbps,
                "a=vqos.bw.minimumBitrateKbps:" + Math.min(1500, kbps),
                "a=vqos.bw.peakBitrateKbps:" + kbps,
                "a=vqos.bw.serverPeakBitrateKbps:" + kbps,
                "a=vqos.bw.enableBandwidthEstimation:1",
                "a=vqos.bw.disableBitrateLimit:0",
                "a=vqos.grc.maximumBitrateKbps:" + kbps,
                "a=vqos.grc.enable:0",
                "a=video.maxNumReferenceFrames:" + Math.max(1, Math.min(4, referenceFrames)),
                "a=video.mapRtpTimestampsToFrames:1",
                "a=video.encoderCscMode:3",
                "a=video.dynamicRangeMode:0",
                "a=video.bitDepth:8",
                "a=video.scalingFeature1:0",
                "a=video.prefilterParams.prefilterModel:0",
                "m=audio 0 RTP/AVP",
                "a=msid:audio",
                "m=mic 0 RTP/AVP",
                "a=msid:mic",
                "a=rtpmap:0 PCMU/8000",
                "m=application 0 RTP/AVP",
                "a=msid:input_1",
                "a=ri.partialReliableThresholdMs:"
                    + attribute(offer, "ri.partialReliableThresholdMs", "30"),
                "a=ri.hidDeviceMask:" + attribute(offer, "ri.hidDeviceMask", "65535"),
                "a=ri.enablePartiallyReliableTransferGamepad:15",
                "a=ri.enablePartiallyReliableTransferHid:"
                    + attribute(offer, "ri.enablePartiallyReliableTransferHid", "65535"),
                ""));
    return Json.join("\n", lines);
  }
}
