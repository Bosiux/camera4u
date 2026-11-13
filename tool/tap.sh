#!/bin/sh
# Sends a tap only when camera4u owns the focused window: tool/tap.sh <x> <y>
if adb shell dumpsys window | grep -q "mCurrentFocus=Window{.*it.bosiux.camera4u"; then
  adb shell input tap "$1" "$2"
else
  echo "camera4u is not focused, tap skipped" >&2; exit 1
fi
