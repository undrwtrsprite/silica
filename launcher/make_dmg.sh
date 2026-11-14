#!/usr/bin/env bash
# Packs Silica.app into a drag-to-Applications disk image: Silica-<version>.dmg.
# Run ./build_app.sh first. The app is ad-hoc signed here, not notarized: that needs an Apple Developer ID,
# so macOS asks downloaders to confirm the first open (see "Read me first.txt" inside the image).
set -euo pipefail
cd "$(dirname "$0")"
APP=Silica.app
[ -d "$APP" ] || { echo "No $APP here: run ./build_app.sh first." >&2; exit 1; }
VERSION=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP/Contents/Info.plist")
DMG="Silica-$VERSION.dmg"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# the licence texts travel with the app
for f in LICENSE THIRD-PARTY-NOTICES.md; do
  [ -f "../$f" ] && cp "../$f" "$APP/Contents/Resources/$f"
done
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"

cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cat > "$STAGE/Read me first.txt" <<'TXT'
Install Silica

1. Drag Silica into the Applications folder.
2. Open it from Applications. Because Silica is not signed with an Apple Developer ID yet, macOS may say it
   cannot verify the app. To open it anyway: right-click Silica, choose Open, then choose Open again.
   (If there is no Open button: System Settings > Privacy & Security > "Open Anyway".)

Silica needs a Mac with Apple Silicon, and a Minecraft: Java Edition purchase. It downloads the game from
Mojang on first run. It is free and open source: https://github.com/undrwtrsprite/silica

Silica is an unofficial project. It is not affiliated with Mojang or Microsoft.
TXT

rm -f "$DMG"
hdiutil create -volname "Silica" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$DMG" >/dev/null
echo "built $PWD/$DMG ($(du -h "$DMG" | cut -f1))"
shasum -a 256 "$DMG"
