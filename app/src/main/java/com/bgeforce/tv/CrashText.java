package com.bgeforce.tv;

/** Diagnostic stack locations only: exception messages can contain account URLs or tokens. */
final class CrashText {
  static String summarize(Throwable error) {
    StringBuilder text = new StringBuilder();
    for (int cause = 0; error != null && cause < 4; cause++) {
      text.append(error.getClass().getName()).append('\n');
      StackTraceElement[] frames = error.getStackTrace();
      for (int i = 0; i < frames.length && i < 12; i++) {
        text.append("  ")
            .append(frames[i].getClassName())
            .append('.')
            .append(frames[i].getMethodName())
            .append(':')
            .append(frames[i].getLineNumber())
            .append('\n');
        if (text.length() > 4000) return text.substring(0, 4000);
      }
      Throwable next = error.getCause();
      if (next == error) break;
      error = next;
    }
    return text.length() <= 4000 ? text.toString() : text.substring(0, 4000);
  }
}
