package dev.betavk.vox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;

/**
 * The voxel world: sections by (level, sx, sy, sz), in memory and on disk. The world is 128 blocks tall, so a column stack has 4 sections at
 * level 0, 2 at level 1 and 1 above. A column (level, sx, sz) is stored and marked present as a whole: all of its sections or none.
 * Disk layout: one file per column, lodvox/v1/L<level>/<sx>_<sz>.bin, sections deflated.
 */
public final class VoxelStore {
    public static final int MAX_LEVEL = 6;
    private static final int MAGIC = 0x564F5831;   // "VOX1"

    private final ConcurrentHashMap<Long, VoxelSection> sections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> columns = new ConcurrentHashMap<>();
    private final File dir;

    public VoxelStore(File dir) {
        this.dir = dir;
    }

    public static int ySections(int level) {
        return Math.max(1, 128 >> (5 + level));
    }

    static long key(int level, int sx, int sy, int sz) {
        return (long) level << 60 | (long) (sy & 15) << 56 | (long) (sx & 0xFFFFFFF) << 28 | (sz & 0xFFFFFFFL);
    }

    static long columnKey(int level, int sx, int sz) {
        return key(level, sx, 0, sz);
    }

    public VoxelSection get(int level, int sx, int sy, int sz) {
        return sections.get(key(level, sx, sy, sz));
    }

    public VoxelSection getOrCreate(int level, int sx, int sy, int sz) {
        return sections.computeIfAbsent(key(level, sx, sy, sz), k -> new VoxelSection(level, sx, sy, sz));
    }

    public void markColumnPresent(int level, int sx, int sz) {
        columns.put(columnKey(level, sx, sz), Boolean.TRUE);
    }

    public boolean columnPresent(int level, int sx, int sz) {
        return columns.containsKey(columnKey(level, sx, sz));
    }

    public void clear() {
        sections.clear();
        columns.clear();
    }

    public int sectionCount() {
        return sections.size();
    }

    public void dropColumn(int level, int sx, int sz) {
        for (int sy = 0; sy < ySections(level); sy++) sections.remove(key(level, sx, sy, sz));
        columns.remove(columnKey(level, sx, sz));
    }

    /** Builds the column one level up from the four columns below it, if all four are present. Returns whether the parent was built. */
    public boolean mipUp(int level, int sx, int sz) {
        if (level + 1 > MAX_LEVEL) return false;
        int px = sx >> 1, pz = sz >> 1;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) if (!columnPresent(level, px * 2 + dx, pz * 2 + dz)) return false;
        for (int py = 0; py < ySections(level + 1); py++) {
            VoxelSection out = getOrCreate(level + 1, px, py, pz);
            for (int cz = 0; cz < 2; cz++) {
                for (int cx = 0; cx < 2; cx++) {
                    VoxelSection[] below = new VoxelSection[2];   // the two child sections stacked in y under this parent section
                    for (int dy = 0; dy < 2; dy++) {
                        int cy = py * 2 + dy;
                        below[dy] = cy < ySections(level) ? get(level, px * 2 + cx, cy, pz * 2 + cz) : null;
                    }
                    mipQuarter(out, below, cx, cz);
                }
            }
        }
        markColumnPresent(level + 1, px, pz);
        return true;
    }

    /** One horizontal quarter (qx, qz) of a parent section: each output voxel is the pick of a 2x2x2 group in the child below it. */
    private static void mipQuarter(VoxelSection out, VoxelSection[] below, int qx, int qz) {
        int[] src = new int[8];
        for (int oy = 0; oy < 32; oy++) {
            VoxelSection c = below[oy >> 4];
            for (int oz = 0; oz < 16; oz++) {
                for (int ox = 0; ox < 16; ox++) {
                    int vx = qx * 16 + ox, vz = qz * 16 + oz;
                    if (c == null) {
                        out.set(vx, oy, vz, 0);
                        continue;
                    }
                    int bx = ox * 2, by = (oy & 15) * 2, bz = oz * 2;
                    int n = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) src[n++] = c.get(bx + dx, by + dy, bz + dz);
                    out.set(vx, oy, vz, Mipper.pick(src));
                }
            }
        }
        out.dirty = true;
    }

    // ---- disk -------------------------------------------------------------------------

    private File columnFile(int level, int sx, int sz) {
        return new File(dir, "L" + level + "/" + sx + "_" + sz + ".bin");
    }

    public void saveColumn(int level, int sx, int sz) throws java.io.IOException {
        File f = columnFile(level, sx, sz);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            int n = ySections(level);
            out.writeByte(n);
            for (int sy = 0; sy < n; sy++) {
                VoxelSection s = get(level, sx, sy, sz);
                if (s == null) throw new IllegalStateException("column " + level + "/" + sx + "," + sz + " has no section " + sy);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(VoxelSection.VOLUME * 4);
                bb.asIntBuffer().put(s.voxels);
                Deflater d = new Deflater(Deflater.BEST_SPEED);
                d.setInput(bb.array());
                d.finish();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 15);
                byte[] chunk = new byte[1 << 14];
                while (!d.finished()) buf.write(chunk, 0, d.deflate(chunk));
                d.end();
                out.writeInt(buf.size());
                buf.writeTo(out);
            }
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }

    public boolean loadColumn(int level, int sx, int sz) {
        File f = columnFile(level, sx, sz);
        if (!f.isFile()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) return false;
            int n = in.readUnsignedByte();
            if (n != ySections(level)) return false;
            VoxelSection[] loaded = new VoxelSection[n];
            for (int sy = 0; sy < n; sy++) {
                int len = in.readInt();
                byte[] comp = new byte[len];
                in.readFully(comp);
                java.util.zip.Inflater inf = new java.util.zip.Inflater();
                inf.setInput(comp);
                byte[] raw = new byte[VoxelSection.VOLUME * 4];
                int got = 0;
                while (got < raw.length && !inf.finished()) {
                    int r = inf.inflate(raw, got, raw.length - got);
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    got += r;
                }
                inf.end();
                if (got != raw.length) return false;
                VoxelSection s = new VoxelSection(level, sx, sy, sz);
                java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(s.voxels);
                loaded[sy] = s;
            }
            for (VoxelSection s : loaded) sections.put(key(level, sx, s.sy, sz), s);
            markColumnPresent(level, sx, sz);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
