#!/bin/sh
# Prints viewfinder frame statistics. Reset, wait, then read.
adb shell dumpsys gfxinfo it.bosiux.camera4u reset > /dev/null
sleep "${1:-10}"
adb shell dumpsys gfxinfo it.bosiux.camera4u | grep -E "Total frames|Janky frames:|50th|90th|99th"
