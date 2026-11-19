#!/usr/bin/env bash
# Prepare your own Minecraft Beta 1.7.3 for this project. Nothing from Mojang is stored in the repository:
# everything below goes to git-ignored folders on your machine.
#
#   scripts/mcprep.sh [version-id | path/to/your/client.jar] [path/to/your/client.jar]
#
# Examples:
#   scripts/mcprep.sh                  # prepares Beta 1.7.3 -> build/mc/minecraft-readable.jar
#   scripts/mcprep.sh a1.2.6           # prepares Alpha 1.2.6 -> build/mc/a1.2.6/minecraft-readable.jar
#   scripts/mcprep.sh /path/client.jar # prepares Beta 1.7.3 from a local client jar
#
# 1. gets RetroMCP-Java (open source) and lets it fetch the mapping table (obfuscated -> readable names) for the version.
# 2. runs our `mcprep` tool: your jar -> readable jar (names remapped, canonicalized, access widened, LWJGL 2 calls redirected to our layer).
# 3. extracts the jar's resources (textures, sounds' index files, lang) for the game to read.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh" >/dev/null
VER="b1.7.3"
CLIENT_JAR=""
if [ $# -ge 1 ]; then
  if [[ "$1" == *.jar ]]; then
    CLIENT_JAR="$1"
  else
    VER="$1"
    [ $# -ge 2 ] && CLIENT_JAR="$2"
  fi
fi
if [ "$VER" = "b1.7.3" ]; then
  RMCP="$ROOT/mojang/retromcp"
  OUT_JAR="$ROOT/build/mc/minecraft-readable.jar"
  RES_DIR="$ROOT/mojang/resources"
else
  RMCP="$ROOT/mojang/retromcp-$VER"
  OUT_JAR="$ROOT/build/mc/$VER/minecraft-readable.jar"
  RES_DIR="$ROOT/mojang/resources-$VER"
fi
mkdir -p "$RMCP"
if [ ! -f "$RMCP/RetroMCP-Java-CLI.jar" ]; then
  if [ -f "$ROOT/mojang/retromcp/RetroMCP-Java-CLI.jar" ]; then
    cp "$ROOT/mojang/retromcp/RetroMCP-Java-CLI.jar" "$RMCP/RetroMCP-Java-CLI.jar"
  else
    echo ">> downloading RetroMCP-Java"
    curl -fL -o "$RMCP/RetroMCP-Java-CLI.jar" "https://github.com/MCPHackers/RetroMCP-Java/releases/latest/download/RetroMCP-Java-CLI.jar"
  fi
fi
if [ ! -f "$RMCP/conf/mappings.tiny" ]; then
  echo ">> RetroMCP setup for $VER (downloads the mapping table)"
  (cd "$RMCP" && "$JAVA_HOME/bin/java" -jar RetroMCP-Java-CLI.jar setup "$VER")
fi
if [ -n "$CLIENT_JAR" ]; then
  mkdir -p "$RMCP/jars"
  cp "$CLIENT_JAR" "$RMCP/jars/minecraft.jar"
fi
[ -f "$RMCP/jars/minecraft.jar" ] || { echo "no client jar: pass the path to your $VER client jar" >&2; exit 1; }
echo ">> remapping $VER"
(cd "$ROOT" && gradle -q :mcprep:run --args="$RMCP/jars/minecraft.jar $RMCP/conf/mappings.tiny $OUT_JAR")
echo ">> extracting resources"
mkdir -p "$RES_DIR"
(cd "$RES_DIR" && unzip -q -o "$RMCP/jars/minecraft.jar" -x '*.class' 'META-INF/*')
echo "done: $OUT_JAR"
