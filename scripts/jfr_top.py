"""CPU profile summary from a JFR file: python3 -I scripts/jfr_top.py file.jfr [thread-substring] [topN]
Per-thread sample counts; for the chosen thread (default 'main'): top SELF frames and top INCLUSIVE methods (a method
counts once per sample no matter how often it appears in the stack), with the percentage of that thread's samples."""
import re, subprocess, sys, collections

jfr, want, topn = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "main"), int(sys.argv[3]) if len(sys.argv) > 3 else 25
out = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", jfr], capture_output=True, text=True).stdout
samples = []
for blk in out.split("jdk.ExecutionSample {")[1:]:
    t = re.search(r'sampledThread = "([^"]*)"', blk)
    st = blk.split("stackTrace = [", 1)[1].split("]", 1)[0] if "stackTrace = [" in blk else ""
    frames = []
    for line in st.splitlines():
        line = line.strip()
        if not line or line.startswith("..."): continue
        frames.append(re.sub(r"\s+line:.*$", "", line))
    samples.append((t.group(1) if t else "?", frames))
bythread = collections.Counter(t for t, _ in samples)
print("samples per thread:", dict(bythread.most_common(6)))
sel = [f for t, f in samples if want in t and f]
n = len(sel)
print(f"\nthread matching '{want}': {n} samples")
selfc = collections.Counter(f[0] for f in sel)
print("\nTOP SELF:")
for m, c in selfc.most_common(topn): print(f"  {c*100/n:5.1f}%  {m}")
inc = collections.Counter()
for f in sel:
    for m in set(f): inc[m] += 1
print("\nTOP INCLUSIVE:")
for m, c in inc.most_common(topn + 10): print(f"  {c*100/n:5.1f}%  {m}")
