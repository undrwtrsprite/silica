#!/usr/bin/env bash
# Builds the launcher and wraps it in a minimal Silica.app (arm64). Unsigned beyond the linker's ad-hoc signature: signing and
# notarisation are release steps, not done here.
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release --arch arm64
APP=Silica.app
rm -rf "$APP" BetaVulkan.app
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp Icon/Silica.icns "$APP/Contents/Resources/Silica.icns"   # made by Icon/make_icon.swift
cp Icon/SilicaMark.png "$APP/Contents/Resources/SilicaMark.png"   # transparent in-window brand mark
cp .build/out/Products/Release/BetaVulkanLauncher "$APP/Contents/MacOS/Silica"
# The game build (our jars, the patch agent, the jar preparation tool: no Mojang code) travels inside the app, so setup needs nothing else from this repository.
if [ -z "${SKIP_STAGE:-}" ]; then
  ( cd .. && source scripts/env.sh >/dev/null && gradle -q :hooks:stageGame ) >/dev/null 2>&1 || echo "warning: gradle :hooks:stageGame failed; the app will ask for a game build folder" >&2
fi
if [ -f ../hooks/build/stage/launch.json ]; then
  mkdir -p "$APP/Contents/Resources"
  rm -rf "$APP/Contents/Resources/stage"
  cp -R ../hooks/build/stage "$APP/Contents/Resources/stage"
fi
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Silica</string>
  <key>CFBundleDisplayName</key><string>Silica</string>
  <key>CFBundleIdentifier</key><string>dev.betavk.launcher</string>
  <key>CFBundleURLTypes</key>
  <array><dict>
    <key>CFBundleURLName</key><string>dev.betavk.launcher.microsoft</string>
    <key>CFBundleURLSchemes</key><array><string>msauth.dev.betavk.launcher</string></array>
  </dict></array>
  <key>CFBundleExecutable</key><string>Silica</string>
  <key>CFBundleIconFile</key><string>Silica</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.4</string>
  <key>CFBundleVersion</key><string>5</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>LSApplicationCategoryType</key><string>public.app-category.games</string>
</dict></plist>
PLIST
touch "$APP"
echo "built $PWD/$APP"
