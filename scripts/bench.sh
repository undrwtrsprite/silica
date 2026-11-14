#!/usr/bin/env bash
# Phase 0 benchmark: fixed-seed world, scripted time-driven camera path, 3 render distances (0 far, 1 normal, 2 short).
#   scripts/bench.sh <label>                       -> bench/results/<label>/d{0,1,2}.{json,png,log}
#   BENCH_JFR=1 BENCH_DISTANCES="1" scripts/bench.sh <label>-jfr   (adds a Flight Recorder profile: d1.jfr)
# Runs the patched shareable build; BENCH_LEGACY=1 runs the old in-tree build instead. Env: BENCH_JVM="-XX:+UseZGC ..." (extra JVM flags), BENCH_MESH=N (chunk meshing worker threads, default 0 = game thread), BENCH_ADVANCED=1 (the game's 'Advanced OpenGL' = occlusion culling; off by default), BENCH_MONITOR (open the window on monitor N: windowed results depend heavily on which display the OS picks), BENCH_FULLSCREEN=1, BENCH_BACKEND (gl|vulkan, default gl; vulkan also uses an uncapped present mode), BENCH_WARMUP (s, default 20), BENCH_SECONDS (s, default 60), BENCH_DISTANCES (default "0 1 2").
# Run it with the machine otherwise idle and the game window left alone: results record window_inactive_frames.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="${1:?usage: bench.sh <label>}"
WARM="${BENCH_WARMUP:-20}"; SECS="${BENCH_SECONDS:-60}"; DISTS="${BENCH_DISTANCES:-0 1 2}"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh" >/dev/null
OUT="$ROOT/bench/results/$LABEL"; mkdir -p "$OUT"
cd "$ROOT"
for d in $DISTS; do
  rm -rf "$ROOT/mojang/run-home"      # fresh world every run: identical generation work
  ARGS="-Dbetavk.nograb=true -Dbetavk.fullscreen=${BENCH_FULLSCREEN:-true} -Dbetavk.bench=true -Dbetavk.bench.distance=$d -Dbetavk.bench.warmup=$WARM -Dbetavk.bench.seconds=$SECS -Dbetavk.bench.label=$LABEL -Dbetavk.bench.out=$OUT/d$d.json"
  [ "${BENCH_BACKEND:-gl}" = "vulkan" ] && ARGS="$ARGS -Dbetavk.backend=vulkan -Dbetavk.uncapped=true"
  [ -n "${BENCH_MONITOR:-}" ] && ARGS="$ARGS -Dbetavk.monitor=$BENCH_MONITOR"
  [ -n "${BENCH_ADVANCED:-}" ] && ARGS="$ARGS -Dbetavk.bench.advanced=true"
  [ -n "${BENCH_MESH:-}" ] && ARGS="$ARGS -Dbetavk.meshworkers=$BENCH_MESH"
  [ -n "${BENCH_JVM:-}" ] && ARGS="$ARGS $BENCH_JVM"
  [ -n "${BENCH_JFR:-}" ] && ARGS="$ARGS -XX:StartFlightRecording=filename=$OUT/d$d.jfr,settings=profile"
  echo ">> $LABEL distance=$d warmup=${WARM}s measure=${SECS}s"
  if [ -n "${BENCH_LEGACY:-}" ]; then   # the old in-tree build (needs the local mc-src), for 1:1 comparisons
    gradle --no-daemon -q :legacy:runGame -PgameJvmArgs="$ARGS" > "$OUT/d$d.log" 2>&1
  else
    JVM_ARGS="$ARGS" BETAVK_BACKEND="${BENCH_BACKEND:-gl}" "$ROOT/scripts/run-patched.sh" > "$OUT/d$d.log" 2>&1
  fi || { echo "run failed, see $OUT/d$d.log" >&2; exit 1; }
  # A dropped draw is missing geometry (holes in the terrain), whatever the FPS says.
  if grep -q "dropping a draw\|Exception" "$OUT/d$d.log"; then echo "!! $LABEL distance=$d: draws were dropped or an exception was logged, see $OUT/d$d.log" >&2; fi
done
python3 -I "$ROOT/scripts/bench_table.py" "$OUT"
