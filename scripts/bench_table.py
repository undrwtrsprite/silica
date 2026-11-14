"""Print a markdown table for one result dir, or a before/after comparison for two: bench_table.py <dir> [<dir2>]"""
import json, os, sys

NAMES = {0: "far (256)", 1: "normal (128)", 2: "short (64)", 3: "tiny (32)"}
ROWS = [("avg FPS", "avg_fps", "{:.1f}"), ("1% low FPS", "one_percent_low_fps", "{:.1f}"), ("avg frame ms", "avg_frame_ms", "{:.2f}"),
        ("p99 frame ms", "p99_ms", "{:.2f}"), ("max frame ms", "max_ms", "{:.1f}"), ("chunk rebuild avg ms", "rebuild_avg_ms", "{:.3f}"),
        ("rebuild ms/frame max", "rebuild_ms_per_frame_max", "{:.2f}"), ("GC pauses (n)", "gc_pause_events", "{:d}"),
        ("GC pause max ms", "gc_pause_max_ms", "{:.1f}"), ("heap peak MB", "heap_used_peak_sampled_mb", "{:d}"), ("RSS MB", "rss_mb", "{:d}")]

def load(d):
    out = {}
    for f in sorted(os.listdir(d)):
        if f.startswith("d") and f.endswith(".json"):
            j = json.load(open(os.path.join(d, f)))
            out[j["render_distance_setting"]] = j
    return out

dirs = sys.argv[1:]
data = [load(d) for d in dirs]
dists = sorted(set().union(*[set(x) for x in data]))
for d in dists:
    print(f"\n### Render distance: {NAMES.get(d, d)}")
    hdr = "| metric | " + " | ".join(os.path.basename(p.rstrip('/')) for p in dirs) + (" | change |" if len(dirs) == 2 else " |")
    print(hdr); print("|---" * (len(dirs) + 1 + (len(dirs) == 2)) + "|")
    for name, key, fmt in ROWS:
        vals = [x.get(d, {}).get(key) for x in data]
        cells = ["-" if v is None else fmt.format(int(v) if "d" in fmt else v) for v in vals]
        chg = ""
        if len(vals) == 2 and None not in vals and vals[0]:
            chg = f" | {(vals[1] / vals[0] - 1) * 100:+.0f}%"
        print(f"| {name} | " + " | ".join(cells) + (chg if len(dirs) == 2 else "") + " |")
    flags = [x.get(d, {}).get("window_inactive_frames", 0) for x in data]
    if any(flags): print(f"\n(warning: window inactive frames: {flags})")
