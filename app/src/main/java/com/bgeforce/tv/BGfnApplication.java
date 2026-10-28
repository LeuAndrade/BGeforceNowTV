package com.bgeforce.tv;

public final class BGfnApplication extends android.app.Application {
  @Override
  public void onCreate() {
    super.onCreate();
    CrashTrace.install(this);
  }
}
