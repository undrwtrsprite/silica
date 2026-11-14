#!/usr/bin/env bash
# Packs Silica.app into a drag-to-Applications disk image: Silica-<version>.dmg.
# Run ./build_app.sh first. The app is ad-hoc signed here, not notarized: that needs an Apple Developer ID,
# so macOS asks downloaders to confirm the first open (the installer window says how).
set -euo pipefail
cd "$(dirname "$0")"
APP=Silica.app
[ -d "$APP" ] || { echo "No $APP here: run ./build_app.sh first." >&2; exit 1; }
VERSION=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP/Contents/Info.plist")
DMG="Silica-$VERSION.dmg"

# the licence texts travel with the app
for f in LICENSE THIRD-PARTY-NOTICES.md; do
  [ -f "../$f" ] && cp "../$f" "$APP/Contents/Resources/$f"
done
[ -d ../licenses ] && rm -rf "$APP/Contents/Resources/licenses" && cp -R ../licenses "$APP/Contents/Resources/licenses"
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"

rm -f "$DMG"
# The pretty installer window (background, arrow, icon positions) is made with dmgbuild, which writes the window
# layout itself so it needs no Finder scripting. Its tools live in a local virtualenv, created on first use.
if [ ! -x .dmgtools/bin/dmgbuild ]; then
  python3 -m venv .dmgtools
  .dmgtools/bin/pip install -q dmgbuild pillow
fi
[ -f dmg/background.tiff ] || .dmgtools/bin/python dmg/make_background.py
.dmgtools/bin/dmgbuild -s dmg/settings.py -D app="$PWD/$APP" -D dmgdir="$PWD/dmg" "Silica" "$DMG" >/dev/null
echo "built $PWD/$DMG ($(du -h "$DMG" | cut -f1))"
shasum -a 256 "$DMG"
