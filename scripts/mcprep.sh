#!/usr/bin/env bash
# Prepare your own Minecraft Beta 1.7.3 for this project. Nothing from Mojang is stored in the repository:
# everything below goes to git-ignored folders on your machine.
#
#   scripts/mcprep.sh [path/to/your/b1.7.3-client.jar]
#
# 1. gets RetroMCP-Java (open source) and lets it fetch the mapping table (obfuscated -> readable names) for b1.7.3; if you pass your own client
#    jar it is used instead of the one RetroMCP would download.
# 2. runs our `mcprep` tool: your jar -> build/mc/minecraft-readable.jar (names remapped, access widened, LWJGL 2 calls redirected to our layer).
# 3. extracts the jar's resources (textures, sounds' index files, lang) to mojang/resources for the game to read.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh" >/dev/null
RMCP="$ROOT/mojang/retromcp"
mkdir -p "$RMCP"
if [ ! -f "$RMCP/RetroMCP-Java-CLI.jar" ]; then
  echo ">> downloading RetroMCP-Java"
  curl -fL -o "$RMCP/RetroMCP-Java-CLI.jar" "https://github.com/MCPHackers/RetroMCP-Java/releases/latest/download/RetroMCP-Java-CLI.jar"
fi
if [ ! -f "$RMCP/conf/mappings.tiny" ]; then
  echo ">> RetroMCP setup for b1.7.3 (downloads the mapping table)"
  (cd "$RMCP" && "$JAVA_HOME/bin/java" -jar RetroMCP-Java-CLI.jar setup b1.7.3)
fi
if [ $# -ge 1 ]; then
  mkdir -p "$RMCP/jars"
  cp "$1" "$RMCP/jars/minecraft.jar"
fi
[ -f "$RMCP/jars/minecraft.jar" ] || { echo "no client jar: pass the path to your b1.7.3 client jar" >&2; exit 1; }
[ "$(unzip -l "$RMCP/jars/minecraft.jar" | grep -c "net/minecraft/client/Minecraft.class")" -gt 0 ] || echo "warning: this does not look like the b1.7.3 client jar" >&2
echo ">> remapping"
(cd "$ROOT" && gradle -q :mcprep:run --args="$RMCP/jars/minecraft.jar $RMCP/conf/mappings.tiny $ROOT/build/mc/minecraft-readable.jar")
echo ">> extracting resources"
mkdir -p "$ROOT/mojang/resources"
(cd "$ROOT/mojang/resources" && unzip -q -o "$RMCP/jars/minecraft.jar" -x '*.class' 'META-INF/*')
echo "done: $ROOT/build/mc/minecraft-readable.jar"
