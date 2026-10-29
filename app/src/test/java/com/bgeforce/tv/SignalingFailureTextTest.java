package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class SignalingFailureTextTest {
  @Test
  public void eofWithoutMessageStillReportsItsExceptionClass() {
    assertEquals("EOFException", Diagnostics.failure(new EOFException()));
  }

  @Test
  public void causeIsBoundedAndSessionProtocolIsRedacted() {
    IOException error =
        new IOException("x-nv-sessionid.0bfe7390-13de-45f2-90a1-d103acc77777", new EOFException());
    String text = Diagnostics.failure(error);
    assertTrue(text.contains("IOException"));
    assertTrue(text.contains("EOFException"));
    assertFalse(text.contains("0bfe7390"));
  }

  @Test
  public void cyclicCausesDoNotHangTheFailureCallback() {
    IOException a = new IOException("a"), b = new IOException("b");
    a.initCause(b);
    b.initCause(a);
    String text = Diagnostics.failure(a);
    assertTrue(text.length() < 100);
    assertTrue(text.contains("IOException"));
  }

  @Test public void fullServiceHostnameIsNotMistakenForAJwtToken() {
    String host = "66-22-134-148.cloudmatchbeta.nvidiagrid.net";
    assertEquals(host, Diagnostics.safe(host));
    assertFalse(Diagnostics.safe("abcdefghijk.abcdefghijk.abcdefghijk").contains("abcdefghijk"));
    assertFalse(Diagnostics.safe("GFNJWT eyJhbGciOiJIUzI1NiJ9.abcdefghijk.abcdefghijk")
        .contains("eyJhbGci"));
  }
}
