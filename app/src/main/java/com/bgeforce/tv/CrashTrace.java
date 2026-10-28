package com.bgeforce.tv;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.List;

/** Small local startup trail, also survives native crashes and low-memory process kills. */
final class CrashTrace {
  private static SharedPreferences prefs;
  private static String notice;

  static synchronized void install(Context context) {
    if (prefs != null) return;
    Context app = context.getApplicationContext();
    prefs = app.getSharedPreferences("stream_diagnostics", Context.MODE_PRIVATE);
    String stage = prefs.getString("active", "");
    String crash = prefs.getString("javaCrash", "");
    if (!stage.isEmpty() || !crash.isEmpty()) {
      String recordedVersion =
          prefs.getString("crashVersion", prefs.getString("streamVersion", ""));
      if (recordedVersion.isEmpty()) recordedVersion = "anterior à " + BuildConfig.VERSION_NAME;
      long since = prefs.getLong("since", 0);
      String system =
          Build.VERSION.SDK_INT >= 30
              ? RecentExit.describe(app, since)
              : "Motivo do sistema: indisponível neste Android.";
      notice =
          "BGeforce Now TV "
              + BuildConfig.VERSION_NAME
              + "\nFalha registrada na execução: "
              + recordedVersion
              + "\nAndroid API "
              + Build.VERSION.SDK_INT
              + " · "
              + Build.MANUFACTURER
              + " "
              + Build.MODEL
              + "\nABI: "
              + android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS)
              + "\nÚltima etapa: "
              + (stage.isEmpty() ? "aplicativo" : stage)
              + "\n"
              + system
              + (crash.isEmpty() ? "" : "\nJava:\n" + crash);
      prefs
          .edit()
          .putString("report", notice)
          .remove("active")
          .remove("javaCrash")
          .remove("streamVersion")
          .remove("crashVersion")
          .commit();
    }
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            prefs
                .edit()
                .putString("javaCrash", CrashText.summarize(error))
                .putString("crashVersion", BuildConfig.VERSION_NAME)
                .commit();
          } catch (Throwable ignored) {
            /* The platform handler must still receive the original crash. */
          }
          if (previous != null) previous.uncaughtException(thread, error);
          else android.os.Process.killProcess(android.os.Process.myPid());
        });
  }

  static void begin(String profile) {
    prefs
        .edit()
        .putString("active", "sessão · " + profile)
        .putString("streamVersion", BuildConfig.VERSION_NAME)
        .putLong("since", System.currentTimeMillis())
        .commit();
  }

  static void stage(String stage) {
    prefs.edit().putString("active", stage).commit();
  }

  static void end() {
    if (prefs != null) prefs.edit().remove("active").remove("streamVersion").commit();
  }

  static synchronized String takeNotice() {
    String value = notice;
    notice = null;
    return value;
  }

  static String report() {
    return prefs.getString("report", "Nenhuma falha registrada nesta versão.");
  }

  @android.annotation.TargetApi(30)
  private static final class RecentExit {
    static String describe(Context context, long since) {
      try {
        ActivityManager manager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 1);
        if (exits.isEmpty() || exits.get(0).getTimestamp() < since - 1000)
          return "Motivo do sistema: nenhum registro correspondente.";
        ApplicationExitInfo exit = exits.get(0);
        String reason;
        switch (exit.getReason()) {
          case ApplicationExitInfo.REASON_CRASH:
            reason = "exceção Java";
            break;
          case ApplicationExitInfo.REASON_CRASH_NATIVE:
            reason = "falha nativa";
            break;
          case ApplicationExitInfo.REASON_LOW_MEMORY:
            reason = "pressão de memória";
            break;
          case ApplicationExitInfo.REASON_ANR:
            reason = "aplicativo sem responder";
            break;
          case ApplicationExitInfo.REASON_SIGNALED:
            reason = "sinal do sistema; causa não determinada";
            break;
          case ApplicationExitInfo.REASON_USER_REQUESTED:
            reason = "encerramento solicitado pelo usuário";
            break;
          default:
            reason = "outro motivo";
        }
        return "Motivo do sistema: "
            + reason
            + " ("
            + exit.getReason()
            + ")"
            + "\nStatus: "
            + exit.getStatus()
            + " · PSS amostrado: "
            + exit.getPss()
            + " KiB";
      } catch (RuntimeException unavailable) {
        return "Motivo do sistema: indisponível.";
      }
    }
  }
}
