"""Compare two screenshots: python3 -I scripts/imgdiff.py A.png B.png [out_diff.png]
Prints mean |difference| overall and per horizontal band (top to bottom), mean colour per band for each image, and
writes an amplified (x4) absolute-difference image."""
import sys
import numpy as np
from PIL import Image

a = np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(float)
b = np.asarray(Image.open(sys.argv[2]).convert("RGB")).astype(float)
assert a.shape == b.shape, (a.shape, b.shape)
d = np.abs(a - b)
print(f"overall mean |diff|: {d.mean():.2f} / 255   pixels differing by >16: {(d.max(axis=2) > 16).mean() * 100:.1f}%")
h = a.shape[0]
for i in range(8):
    s = slice(i * h // 8, (i + 1) * h // 8)
    ma, mb = a[s].reshape(-1, 3).mean(0), b[s].reshape(-1, 3).mean(0)
    print(f"band {i}: diff {d[s].mean():5.2f}   A rgb=({ma[0]:5.1f},{ma[1]:5.1f},{ma[2]:5.1f})   B rgb=({mb[0]:5.1f},{mb[1]:5.1f},{mb[2]:5.1f})")
if len(sys.argv) > 3:
    Image.fromarray(np.clip(d * 4, 0, 255).astype("uint8")).save(sys.argv[3])
