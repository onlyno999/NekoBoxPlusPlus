package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;
import io.nekohasekai.sagernet.aidl.SpeedDisplayData;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  void resetTrafficStats();
  long queryDialFailures(String tag);
  void resetDialHealth(String tag);
    SpeedDisplayData querySpeed();
  SpeedDisplayData querySelectedSpeed();
}
