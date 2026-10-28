package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.media.MediaCodec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.webrtc.*;

public class DecoderRecoveryTest {
  private void field(Object target, String name, Object value) throws Exception {
    Field field = FastDecoder.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private FastDecoder decoder(VideoDecoder fallback, MediaCodec codec) throws Exception {
    FastDecoder decoder =
        mock(
            FastDecoder.class,
            withSettings()
                .useConstructor(null, "video/avc", null, 60, fallback)
                .defaultAnswer(CALLS_REAL_METHODS));
    field(decoder, "codec", codec);
    field(decoder, "running", true);
    field(decoder, "width", 1280);
    field(decoder, "height", 720);
    return decoder;
  }

  private EncodedImage changedResolution() {
    return EncodedImage.builder()
        .setBuffer(ByteBuffer.allocateDirect(4), null)
        .setEncodedWidth(1920)
        .setEncodedHeight(1080)
        .setFrameType(EncodedImage.FrameType.VideoFrameKey)
        .setCaptureTimeNs(1000)
        .createEncodedImage();
  }

  @Test
  public void resolutionChangeDelegatesImmediatelyWhenReinitActivatedFallback() throws Exception {
    VideoDecoder fallback = mock(VideoDecoder.class);
    MediaCodec codec = mock(MediaCodec.class);
    FastDecoder decoder = decoder(fallback, codec);
    EncodedImage frame = changedResolution();
    when(fallback.decode(frame, null)).thenReturn(VideoCodecStatus.OK);
    doAnswer(
            call -> {
              field(decoder, "usingCompatible", true);
              return VideoCodecStatus.OK;
            })
        .when(decoder)
        .initDecode(any(VideoDecoder.Settings.class), nullable(VideoDecoder.Callback.class));
    assertEquals(VideoCodecStatus.OK, decoder.decode(frame, null));
    verify(codec).release();
    verify(fallback).decode(frame, null);
  }

  @Test
  public void failedFallbackInitializationDoesNotDecodeTheFrame() throws Exception {
    VideoDecoder fallback = mock(VideoDecoder.class);
    FastDecoder decoder = decoder(fallback, mock(MediaCodec.class));
    doReturn(VideoCodecStatus.ERROR)
        .when(decoder)
        .initDecode(any(VideoDecoder.Settings.class), nullable(VideoDecoder.Callback.class));
    assertEquals(VideoCodecStatus.ERROR, decoder.decode(changedResolution(), null));
    verifyNoInteractions(fallback);
  }

  @Test
  public void driverRuntimeExceptionDoesNotEscapeTheDrainThread() throws Exception {
    MediaCodec codec = mock(MediaCodec.class);
    when(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong()))
        .thenThrow(new NullPointerException("synthetic driver failure"));
    FastDecoder decoder = decoder(mock(VideoDecoder.class), codec);
    Method drain = FastDecoder.class.getDeclaredMethod("drain");
    drain.setAccessible(true);
    drain.invoke(decoder);
    assertEquals(VideoCodecStatus.UNINITIALIZED, decoder.decode(changedResolution(), null));
  }
}
