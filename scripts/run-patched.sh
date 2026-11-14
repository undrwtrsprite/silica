#!/usr/bin/env bash
# Starts the game from your prepared jar (scripts/mcprep.sh) with our patches applied by the agent. Extra JVM flags via JVM_ARGS, e.g.
#   JVM_ARGS="-Dbetavk.backend=vulkan" scripts/run-patched.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh" >/dev/null
cd "$ROOT"
gradle -q :agent:agentJar :hooks:classes :game:stageGame
CP="$ROOT/hooks/build/classes/java/main:$ROOT/build/mc/minecraft-readable.jar:$ROOT/mojang/resources:$(ls "$ROOT"/game/build/stage/lib/*.jar | tr '\n' ':')"
SANDBOX="${BETAVK_HOME:-$ROOT/mojang/run-home}"
mkdir -p "$SANDBOX"
# Sounds come from a Mojang asset store (indexes/b1.7.json + objects/): BETAVK_ASSETS, else the launcher's, if present.
ASSETS="${BETAVK_ASSETS:-$HOME/Library/Application Support/BetaVulkan/assets}"
ASSET_ARG=()
[ -f "$ASSETS/indexes/b1.7.json" ] && ASSET_ARG=("-Dbetavk.assets=$ASSETS")
# Heap: live data is 150-250 MB at start and grows about 70 KB per loaded chunk (b1.7.3 never unloads chunks; hooks/world/Unloader can, with -Dbetavk.unload=true, but it is off by default).
# The free-ratio limits stop G1 holding a 700+ MB heap after the start-up burst (resident stays near 830 MB, 8 of 8 runs at 2 and 3 GB caps); the cap is only the ceiling for
# long trips. A later -Xmx in JVM_ARGS overrides it.
exec "$JAVA_HOME/bin/java" -Xmx3072m -XX:MaxHeapFreeRatio=40 -XX:MinHeapFreeRatio=15 -XstartOnFirstThread -Djava.awt.headless=true "${ASSET_ARG[@]}" \
  -javaagent:"$ROOT/agent/build/libs/patch-agent.jar" -Dbetavk.patchset=dev.betavk.hooks.PatchSet \
  -Duser.home="$SANDBOX" -Dbetavk.backend="${BETAVK_BACKEND:-vulkan}" ${JVM_ARGS:-} \
  -cp "$CP" dev.betavk.boot.Boot
