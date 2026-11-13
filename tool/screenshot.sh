#!/bin/sh
# Captures the phone screen into the docs worktree: tool/screenshot.sh <task-name> <file-name>
set -e
DIR="$HOME/Documents/camera4u-docs/images/$1"
mkdir -p "$DIR"
adb exec-out screencap -p > "$DIR/$2.png"
echo "$DIR/$2.png"
