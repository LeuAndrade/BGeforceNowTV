package com.bgeforce.tv;

import static org.junit.Assert.*;

import org.junit.Test;

public class CrashTextTest {
  @Test
  public void reportKeepsStackLocationsWithoutExceptionMessagesOrFilenames() {
    RuntimeException error = new RuntimeException("TOKEN_PRIVATE_123 https://account/secret");
    error.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("com.bgeforce.tv.FastDecoder", "decode", "PRIVATE_FILE", 127)
        });
    String report = CrashText.summarize(error);
    assertTrue(report.contains("java.lang.RuntimeException"));
    assertTrue(report.contains("FastDecoder.decode:127"));
    assertFalse(report.contains("TOKEN_PRIVATE"));
    assertFalse(report.contains("https://"));
    assertFalse(report.contains("PRIVATE_FILE"));
  }

  @Test
  public void cyclicCausesCannotProduceAnUnboundedReport() {
    RuntimeException first = new RuntimeException("secret one");
    RuntimeException second = new RuntimeException("secret two");
    first.initCause(second);
    second.initCause(first);
    String report = CrashText.summarize(first);
    assertTrue(report.length() <= 4000);
    assertFalse(report.contains("secret"));
  }

  @Test
  public void veryLongStackLocationsRemainBounded() {
    String name = new String(new char[5000]).replace('\0', 'x');
    Error error = new OutOfMemoryError("private allocation message");
    error.setStackTrace(new StackTraceElement[] {new StackTraceElement(name, "run", null, 1)});
    String report = CrashText.summarize(error);
    assertEquals(4000, report.length());
    assertFalse(report.contains("private allocation"));
  }
}
