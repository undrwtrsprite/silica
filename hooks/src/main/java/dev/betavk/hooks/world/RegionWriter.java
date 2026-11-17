package dev.betavk.hooks.world;

import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkLoader;
import net.minecraft.src.CompressedStreamTools;
import net.minecraft.src.McRegionChunkLoader;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.RegionFileCache;
import net.minecraft.src.World;
import net.minecraft.src.WorldInfo;

import java.io.DataOutputStream;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saving a chunk used to build the NBT, deflate it and write the region file on the game thread, and autosave does 24 of them every 2 seconds (a visible
 * hitch whenever you walk into new terrain). Now the NBT is built on the game thread with private copies of the big arrays, and deflating and writing happen
 * on a background thread. A chunk with a write in flight is never read from disk before that write has finished (loadChunk flushes first), and the end of every
 * full save flushes everything.
 */
public final class RegionWriter {
    private RegionWriter() {
    }

    private static final boolean ASYNC = !"0".equals(System.getProperty("betavk.asyncsave", "1"));
    private static final String[] ARRAYS = {"Blocks", "Data", "SkyLight", "BlockLight", "HeightMap"};

    private static final class Writer {
        final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Chunk writer");
            t.setDaemon(true);
            return t;
        });
        final ConcurrentHashMap<Long, NBTTagCompound> pending = new ConcurrentHashMap<>();
    }

    private static final WeakHashMap<McRegionChunkLoader, Writer> writers = new WeakHashMap<>();

    private static synchronized Writer writer(McRegionChunkLoader l) {
        return writers.computeIfAbsent(l, k -> new Writer());
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | ((long) z & 0xFFFFFFFFL);
    }

    /** Waits until every queued chunk write has reached the region files. */
    public static void flush(McRegionChunkLoader l) {
        try {
            writer(l).exec.submit(() -> { }).get();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** atStart of McRegionChunkLoader.loadChunk: a chunk with a write in flight must not be read from disk first. */
    public static void beforeLoad(McRegionChunkLoader l, World w, int x, int z) {
        if (writer(l).pending.containsKey(pack(x, z))) flush(l);
    }

    /** atStart of saveExtraData and func_814_a (the end of a save). */
    public static void endOfSave(McRegionChunkLoader l) {
        flush(l);
    }

    /** replaceBody of McRegionChunkLoader.saveChunk. */
    public static void save(McRegionChunkLoader l, World world, Chunk chunk) throws java.io.IOException {
        dev.betavk.lod.LodEngine.onChunkEdited(world, chunk);
        world.checkSessionLock();
        final NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        root.setTag("Level", level);
        ChunkLoader.storeChunkInCompound(chunk, world, level);
        for (String key : ARRAYS) {
            byte[] a = level.getByteArray(key);
            if (a != null) level.setByteArray(key, a.clone());   // the chunk keeps changing while the writer thread works on this snapshot
        }
        final int x = chunk.xPosition, z = chunk.zPosition;
        final long key = pack(x, z);
        final WorldInfo info = world.getWorldInfo();
        final Writer w = writer(l);
        w.pending.put(key, root);
        Runnable task = () -> {
            try {
                synchronized (RegionFileCache.class) {   // the cache closes region files when it evicts them: do not let that happen mid-write
                    DataOutputStream out = RegionFileCache.getChunkOutputStream(l.worldDir, x, z);
                    CompressedStreamTools.func_1139_a(root, out);
                    out.close();
                    info.setSizeOnDisk(info.getSizeOnDisk() + (long) RegionFileCache.getSizeDelta(l.worldDir, x, z));
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                w.pending.remove(key, root);
            }
        };
        if (ASYNC) w.exec.execute(task);
        else task.run();   // -Dbetavk.asyncsave=0: the original synchronous behaviour
    }
}
