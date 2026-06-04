#!/usr/bin/env bash
# Wakes the agent on app-level errors.
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
# Drain current buffer, then tail live for app errors
adb logcat -c >/dev/null 2>&1
adb logcat -v time | awk '
/AndroidRuntime|FATAL EXCEPTION|BaoLog.*E |com\.bao\.translate.* [EW] / {
  print "AGENT_LOOP_WAKE_ADB_ERR " NR " " $0
  fflush()
}
'
