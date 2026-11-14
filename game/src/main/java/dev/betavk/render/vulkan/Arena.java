package dev.betavk.render.vulkan;

import java.util.ArrayList;
import java.util.List;

/** First-fit sub-allocator with coalescing over one big buffer. Offsets are multiples of the alignment (32 = vertex size). */
final class Arena {
    private final long size;
    private final int align;
    private final List<long[]> free = new ArrayList<>();   // {offset, length}, sorted by offset
    long used;

    Arena(long size, int align) {
        this.size = size;
        this.align = align;
        free.add(new long[]{0, size});
    }

    /** @return offset, or -1 when full. */
    long alloc(long bytes) {
        long need = (bytes + align - 1) / align * align;
        for (int i = 0; i < free.size(); i++) {
            long[] f = free.get(i);
            if (f[1] >= need) {
                long off = f[0];
                f[0] += need;
                f[1] -= need;
                if (f[1] == 0) free.remove(i);
                used += need;
                return off;
            }
        }
        return -1;
    }

    void release(long offset, long bytes) {
        long len = (bytes + align - 1) / align * align;
        used -= len;
        int i = 0;
        while (i < free.size() && free.get(i)[0] < offset) i++;
        free.add(i, new long[]{offset, len});
        if (i + 1 < free.size() && free.get(i)[0] + free.get(i)[1] == free.get(i + 1)[0]) {
            free.get(i)[1] += free.get(i + 1)[1];
            free.remove(i + 1);
        }
        if (i > 0 && free.get(i - 1)[0] + free.get(i - 1)[1] == free.get(i)[0]) {
            free.get(i - 1)[1] += free.get(i)[1];
            free.remove(i);
        }
    }

    long capacity() {
        return size;
    }

    long largestFree() {
        long m = 0;
        for (long[] f : free) m = Math.max(m, f[1]);
        return m;
    }

    int freeBlocks() {
        return free.size();
    }
}
