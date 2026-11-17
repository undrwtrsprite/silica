package dev.betavk.lod;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compressed region-bucketed disk cache for LodNodes under {@code saves/<world>/lod/}.
 * Stores up to 16x16 nodes per {@code l<level>/r.<rx>.<rz>.lod} file so worlds don't accumulate
 * thousands of tiny files and explored horizons reload off disk without regenerating terrain.
 */
final class LodDiskCache {
    private LodDiskCache() {
    }

    private static final int MAGIC = 0x42564B4C;   // "BVKL"
    private static final int FORMAT_VERSION = 1;
    private static final int SLOTS = 256;
    private static final int HEADER_BYTES = 16 + SLOTS * 8;   // 2064 bytes
    private static final int MAX_OPEN_REGIONS = 48;
    private static final int MAX_PENDING_WRITES = 512;

    private record SaveEntry(File dir, long seed, int level, int gx, int gz, int[] vox, short[] tint) {
    }

    private static final class RegionFile {
        final File file;
        final int[] offsets = new int[SLOTS];
        final int[] lengths = new int[SLOTS];
        RandomAccessFile raf;
        boolean headerValid;

        RegionFile(File file) {
            this.file = file;
        }

        synchronized void ensureOpen(long seed, boolean create) throws IOException {
            if (raf != null) return;
            if (!file.isFile()) {
                if (!create) return;
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                raf = new RandomAccessFile(file, "rw");
                initEmpty(seed);
                return;
            }
            raf = new RandomAccessFile(file, create ? "rw" : "r");
            if (raf.length() < HEADER_BYTES) {
                if (!create) {
                    close();
                    return;
                }
                initEmpty(seed);
                return;
            }
            byte[] hdr = new byte[HEADER_BYTES];
            raf.seek(0L);
            raf.readFully(hdr);
            ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            int magic = bb.getInt();
            int ver = bb.getInt();
            long fSeed = bb.getLong();
            if (magic != MAGIC || ver != FORMAT_VERSION || fSeed != seed) {
                if (!create) {
                    close();
                    return;
                }
                initEmpty(seed);
                return;
            }
            for (int i = 0; i < SLOTS; i++) {
                offsets[i] = bb.getInt();
                lengths[i] = bb.getInt();
            }
            headerValid = true;
        }

        private void initEmpty(long seed) throws IOException {
            for (int i = 0; i < SLOTS; i++) {
                offsets[i] = 0;
                lengths[i] = 0;
            }
            ByteBuffer bb = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(MAGIC);
            bb.putInt(FORMAT_VERSION);
            bb.putLong(seed);
            raf.setLength(HEADER_BYTES);
            raf.seek(0L);
            raf.write(bb.array());
            headerValid = true;
        }

        synchronized byte[] readSlot(int slot, long seed) {
            try {
                ensureOpen(seed, false);
                if (!headerValid || raf == null) return null;
                int off = offsets[slot];
                int len = lengths[slot];
                if (off < HEADER_BYTES || len <= 0) return null;
                long flen = raf.length();
                if ((long) off + len > flen) return null;
                byte[] comp = new byte[len];
                raf.seek(off);
                raf.readFully(comp);
                return comp;
            } catch (IOException ignored) {
                return null;
            }
        }

        synchronized void writeSlot(int slot, long seed, byte[] comp, int compLen) {
            try {
                ensureOpen(seed, true);
                if (!headerValid || raf == null) return;
                int curOff = offsets[slot];
                int curLen = lengths[slot];
                int writeOff = (curOff >= HEADER_BYTES && curLen >= compLen) ? curOff : (int) Math.max(HEADER_BYTES, raf.length());
                if ((long) writeOff + compLen > Integer.MAX_VALUE) return;
                raf.seek(writeOff);
                raf.write(comp, 0, compLen);

                ByteBuffer entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                entry.putInt(writeOff);
                entry.putInt(compLen);
                raf.seek(16L + (long) slot * 8L);
                raf.write(entry.array());
                offsets[slot] = writeOff;
                lengths[slot] = compLen;
            } catch (IOException ignored) {
            }
        }

        synchronized void invalidateSlot(int slot, long seed) {
            try {
                if (!file.isFile()) return;
                ensureOpen(seed, true);
                if (!headerValid || raf == null || lengths[slot] <= 0) return;
                lengths[slot] = 0;
                offsets[slot] = 0;
                ByteBuffer entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                entry.putInt(0);
                entry.putInt(0);
                raf.seek(16L + (long) slot * 8L);
                raf.write(entry.array());
            } catch (IOException ignored) {
            }
        }

        synchronized void close() {
            headerValid = false;
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
                raf = null;
            }
        }
    }

    private static final LinkedHashMap<String, RegionFile> openRegions = new LinkedHashMap<>(64, 0.75F, true);
    private static final ConcurrentLinkedQueue<SaveEntry> writeQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queuedCount = new AtomicInteger();
    private static volatile File activeLodDir;
    private static volatile long activeSeed;
    private static boolean writerStarted;

    private static final ThreadLocal<byte[]> rawScratch = new ThreadLocal<>();
    private static final ThreadLocal<byte[]> compScratch = new ThreadLocal<>();
    private static final ThreadLocal<Inflater> inflaterScratch = ThreadLocal.withInitial(Inflater::new);

    static synchronized void reset(File saveDir, long seed) {
        flushWrites();
        closeAllRegions();
        activeLodDir = saveDir == null ? null : new File(saveDir, "lod");
        activeSeed = seed;
    }

    private static void startWriter() {
        if (writerStarted) return;
        synchronized (LodDiskCache.class) {
            if (writerStarted) return;
            writerStarted = true;
            Thread t = new Thread(LodDiskCache::writerLoop, "LOD disk writer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.start();
        }
    }

    private static void writerLoop() {
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        while (true) {
            SaveEntry e = writeQueue.poll();
            if (e == null) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            queuedCount.decrementAndGet();
            writeNow(e, def);
        }
    }

    private static void flushWrites() {
        if (writeQueue.isEmpty()) return;
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        try {
            SaveEntry e;
            while ((e = writeQueue.poll()) != null) {
                queuedCount.decrementAndGet();
                writeNow(e, def);
            }
        } finally {
            def.end();
        }
    }

    private static void writeNow(SaveEntry e, Deflater def) {
        if (e.dir == null) return;
        int rx = e.gx >> 4, rz = e.gz >> 4;
        int slot = ((e.gx & 15) << 4) | (e.gz & 15);
        int rawBytes = e.vox.length * 4 + e.tint.length * 2;
        byte[] raw = ensureRawBuf(rawBytes);
        ByteBuffer bb = ByteBuffer.wrap(raw, 0, rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : e.vox) bb.putInt(v);
        for (short s : e.tint) bb.putShort(s);

        byte[] comp = compScratch.get();
        if (comp == null || comp.length < rawBytes + 64) {
            comp = new byte[rawBytes + 256];
            compScratch.set(comp);
        }
        def.reset();
        def.setInput(raw, 0, rawBytes);
        def.finish();
        int compLen = def.deflate(comp);
        if (compLen <= 0 || !def.finished()) return;

        RegionFile rf = regionFor(e.dir, e.level, rx, rz);
        rf.writeSlot(slot, e.seed, comp, compLen);
    }

    private static byte[] ensureRawBuf(int need) {
        byte[] raw = rawScratch.get();
        if (raw == null || raw.length < need) {
            raw = new byte[Math.max(need, 136 * 1024)];
            rawScratch.set(raw);
        }
        return raw;
    }

    private static RegionFile regionFor(File dir, int level, int rx, int rz) {
        String key = dir.getPath() + '|' + level + '|' + rx + '|' + rz;
        synchronized (openRegions) {
            RegionFile rf = openRegions.get(key);
            if (rf != null) return rf;
            while (openRegions.size() >= MAX_OPEN_REGIONS) {
                Iterator<Map.Entry<String, RegionFile>> it = openRegions.entrySet().iterator();
                if (!it.hasNext()) break;
                Map.Entry<String, RegionFile> oldest = it.next();
                it.remove();
                oldest.getValue().close();
            }
            File f = new File(new File(dir, "l" + level), "r." + rx + "." + rz + ".lod");
            rf = new RegionFile(f);
            openRegions.put(key, rf);
            return rf;
        }
    }

    private static void closeAllRegions() {
        synchronized (openRegions) {
            for (RegionFile rf : openRegions.values()) rf.close();
            openRegions.clear();
        }
    }

    static boolean load(LodNode node) {
        File dir = activeLodDir;
        if (dir == null) return false;
        long seed = activeSeed;
        int rx = node.gx >> 4, rz = node.gz >> 4;
        int slot = ((node.gx & 15) << 4) | (node.gz & 15);
        File f = new File(new File(dir, "l" + node.level), "r." + rx + "." + rz + ".lod");
        if (!f.isFile()) return false;

        RegionFile rf = regionFor(dir, node.level, rx, rz);
        byte[] comp = rf.readSlot(slot, seed);
        if (comp == null) return false;

        int rawBytes = node.vox.length * 4 + node.colTint.length * 2;
        byte[] raw = ensureRawBuf(rawBytes);
        Inflater inf = inflaterScratch.get();
        try {
            inf.reset();
            inf.setInput(comp);
            int total = 0;
            while (!inf.finished()) {
                int n = inf.inflate(raw, total, rawBytes - total);
                if (n == 0) {
                    if (inf.needsInput() || total >= rawBytes) break;
                }
                total += n;
            }
            if (total != rawBytes) return false;
        } catch (Throwable ignored) {
            return false;
        }

        ByteBuffer bb = ByteBuffer.wrap(raw, 0, rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < node.vox.length; i++) node.vox[i] = bb.getInt();
        for (int i = 0; i < node.colTint.length; i++) node.colTint[i] = bb.getShort();
        node.dataReady = true;
        return true;
    }

    static void saveAsync(LodNode node) {
        File dir = activeLodDir;
        if (dir == null || !node.dataReady || node.dataDirty) return;
        if (queuedCount.get() >= MAX_PENDING_WRITES) return;
        startWriter();
        queuedCount.incrementAndGet();
        writeQueue.add(new SaveEntry(dir, activeSeed, node.level, node.gx, node.gz, node.vox.clone(), node.colTint.clone()));
    }

    static void invalidateChunk(int cx, int cz, int maxLevel) {
        File dir = activeLodDir;
        if (dir == null) return;
        long seed = activeSeed;
        for (int lvl = 0; lvl <= maxLevel; lvl++) {
            int shift = lvl <= 1 ? 0 : (lvl - 1);
            int gx = cx >> shift, gz = cz >> shift;
            int rx = gx >> 4, rz = gz >> 4;
            File f = new File(new File(dir, "l" + lvl), "r." + rx + "." + rz + ".lod");
            if (!f.isFile()) continue;
            RegionFile rf = regionFor(dir, lvl, rx, rz);
            rf.invalidateSlot(((gx & 15) << 4) | (gz & 15), seed);
        }
    }
}
