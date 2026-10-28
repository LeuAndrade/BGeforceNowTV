package com.bgeforce.tv;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.media.MediaCodec;
import android.util.LongSparseArray;
import android.view.Surface;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.Test;
import org.webrtc.*;

public class DirectDecoderInputTest {
  private void set(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(o, value);
  }

  private Object get(Object o, String field) throws Exception {
    Field f = o.getClass().getDeclaredField(field);
    f.setAccessible(true);
    return f.get(o);
  }

  private SurfaceDecoder decoder(MediaCodec codec) throws Exception {
    Surface display = mock(Surface.class);
    when(display.isValid()).thenReturn(true);
    SurfaceDecoder d =
        new SurfaceDecoder(null, "video/avc", 60, true, () -> display, null, () -> {}, () -> {});
    set(d, "codec", codec);
    set(d, "width", 1920);
    set(d, "height", 1080);
    set(d, "inCodec", mock(LongSparseArray.class));
    return d;
  }

  private EncodedImage image(ByteBuffer b) {
    return EncodedImage.builder()
        .setBuffer(b, null)
        .setEncodedWidth(1920)
        .setEncodedHeight(1080)
        .setFrameType(EncodedImage.FrameType.VideoFrameKey)
        .setCaptureTimeNs(1000)
        .createEncodedImage();
  }

  @Test
  public void readyInputCopiesIntoCodecWithoutAnIntermediateEncodedBuffer() throws Exception {
    MediaCodec codec = mock(MediaCodec.class);
    ByteBuffer target = ByteBuffer.allocateDirect(16);
    when(codec.getInputBuffer(3)).thenReturn(target);
    SurfaceDecoder d = decoder(codec);
    ((ArrayDeque<Integer>) get(d, "inputs")).add(3);
    ByteBuffer source = ByteBuffer.allocateDirect(4);
    source.put(new byte[] {1, 2, 3, 4}).flip();
    assertEquals(VideoCodecStatus.OK, d.decode(image(source), null));
    assertEquals(0, source.position());
    assertEquals(0, ((ArrayDeque<?>) get(d, "pool")).size());
    assertEquals(0, ((ArrayDeque<?>) get(d, "pending")).size());
    target.flip();
    byte[] actual = new byte[4];
    target.get(actual);
    assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
    verify(codec).queueInputBuffer(eq(3), eq(0), eq(4), anyLong(), eq(0));
  }

  @Test
  public void waitingFrameOwnsACopyUntilAnInputSlotBecomesAvailable() throws Exception {
    MediaCodec codec = mock(MediaCodec.class);
    ByteBuffer target = ByteBuffer.allocateDirect(16);
    when(codec.getInputBuffer(2)).thenReturn(target);
    SurfaceDecoder d = decoder(codec);
    ByteBuffer source = ByteBuffer.allocateDirect(4);
    source.put(new byte[] {1, 2, 3, 4}).flip();
    assertEquals(VideoCodecStatus.OK, d.decode(image(source), null));
    source.put(0, (byte) 9);
    Method factory = SurfaceDecoder.class.getDeclaredMethod("callbacks", int.class);
    factory.setAccessible(true);
    MediaCodec.Callback cb = (MediaCodec.Callback) factory.invoke(d, 0);
    cb.onInputBufferAvailable(codec, 2);
    target.flip();
    byte[] actual = new byte[4];
    target.get(actual);
    assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
    assertEquals(1, ((ArrayDeque<?>) get(d, "pool")).size());
  }
}
