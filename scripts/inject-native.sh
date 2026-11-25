#!/usr/bin/env bash
# Copies the custom plugins into the generated Android project, then patches
# the manifest and Gradle files (permissions, Shizuku provider, Shizuku library).
set -euo pipefail
PKG_DIR="android/app/src/main/java/com/pulsecheck/app"
mkdir -p "$PKG_DIR"
cp native/RadioPlugin.java "$PKG_DIR/RadioPlugin.java"
cp native/ToolsPlugin.java "$PKG_DIR/ToolsPlugin.java"
cp native/MainActivity.java "$PKG_DIR/MainActivity.java"
python3 scripts/patch_android.py
echo "Native plugins injected."
