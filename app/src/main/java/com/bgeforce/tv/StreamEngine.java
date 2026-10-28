package com.bgeforce.tv;

import android.view.View;
import org.json.JSONObject;
import org.webrtc.IceCandidate;

public interface StreamEngine extends AutoCloseable {
  interface Events {
    void ready();

    void firstFrame();

    void stats(JSONObject stats);

    void error(String message);

    void answer(String sdp, String nvst);

    void ice(IceCandidate c);

    default void keyframe() {}

    default void videoSize(int width, int height) {}
  }

  default String startupState() {
    return "";
  }

  View view();

  void start(SessionClient.Seat seat, boolean microphone);

  void offer(String sdp);

  void ice(IceCandidate c);

  void input(byte[] packet);

  void microphone(boolean enabled);

  void statistics(boolean enabled);

  void audio(boolean enabled);

  @Override
  void close();
}
