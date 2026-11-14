package dev.betavk.hooks.mesh;

import net.minecraft.src.World;
import net.minecraft.src.WorldChunkManager;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk meshing pool. -Dbetavk.meshworkers=N (default 1; 0 = meshing stays on the game thread, the original behaviour). Jobs are built (a snapshot of
 * chunk references) on the game thread; workers produce MeshResults; the game thread applies them to display lists, because only it may touch the
 * graphics backend.
 */
public final class MeshPool {
    private MeshPool() {
    }

    private static final double RATE = Double.parseDouble(System.getProperty("betavk.meshrate", "900"));
    private static final int BUDGET = Integer.getInteger("betavk.meshbudget", 2);
    public static final int WORKERS = Integer.getInteger("betavk.meshworkers", 1);
    static final LinkedBlockingQueue<MeshWorker.Job> jobs = new LinkedBlockingQueue<>();
    static final ConcurrentLinkedQueue<MeshResult> done = new ConcurrentLinkedQueue<>();
    private static boolean started;
    private static long lastBudgetCall;
    private static double frameEma = 0.004;

    public static final AtomicLong meshNanos = new AtomicLong(), meshCount = new AtomicLong();
    public static long appliedCount, appliedNanos, droppedStale, fallbacks;

    /**
     * How many far sections the game may hand to the workers this frame. The original rebuilt 2 per frame, which made how fast the world fills in
     * depend on the frame rate; now it is a rate (RATE sections a second whatever the frame time), never fewer than 2 per frame.
     */
    public static int farBudget() {
        if (!enabled()) return 2;
        long now = System.nanoTime();
        if (lastBudgetCall != 0) frameEma += (Math.min(0.1, (now - lastBudgetCall) / 1e9) - frameEma) * 0.1;
        lastBudgetCall = now;
        if (RATE <= 0) return Math.max(2, Math.min(48, BUDGET));
        return Math.max(2, Math.min(24, (int) Math.round(RATE * frameEma)));
    }

    public static boolean enabled() {
        return WORKERS > 0;
    }

    /** Only the overworld's plain WorldChunkManager can be duplicated per worker; other dimensions stay synchronous. */
    public static boolean canMesh(World w) {
        return enabled() && w != null && w.getWorldChunkManager().getClass() == WorldChunkManager.class;
    }

    public static void submit(MeshWorker.Job job) {
        if (!started) {
            started = true;
            for (int i = 0; i < WORKERS; i++) new MeshWorker(i).start();
            System.out.println("[betavk] chunk meshing on " + WORKERS + " worker threads");
        }
        jobs.add(job);
    }

    /** Game thread: apply finished meshes for up to the given time budget. */
    public static void applyCompleted(long budgetNanos) {
        if (!started) return;
        long t0 = System.nanoTime();
        MeshResult r;
        while ((r = done.poll()) != null) {
            long a0 = System.nanoTime();
            meshNanos.addAndGet(r.meshNanos);
            meshCount.incrementAndGet();
            if (r.failure != null) {
                fallbacks++;
                if (fallbacks <= 3) r.failure.printStackTrace();
            }
            AsyncMesh.finish(r);
            appliedCount++;
            appliedNanos += System.nanoTime() - a0;
            if (System.nanoTime() - t0 > budgetNanos) break;
        }
    }

    public static int pending() {
        return jobs.size() + done.size();
    }
}
